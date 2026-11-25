package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.agent.initialbalance.dto.InitialBalanceImportResponse;
import cn.gson.financial.agent.initialbalance.service.InitialBalanceApplyService;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;

/**
 * 开放 API — 期初余额导入落库（FN-NEW-INITIAL-BALANCE-IMPORT-APPLY）
 * <p>路径：{@code POST /openapi/v1/account-sets/{accountSetsId}/initial-balance/import}
 *
 * <p>方案 A「XW 解析 + FN 落库」的落库端点：BT 上传 Excel → XW 解析缓存（parse_task_id）→ 会计在 BT 勾选要补建的缺失科目
 * → 本端点回取 XW 缓存、按确认补建科目、写期初余额。
 *
 * <p>复用（不重写）：
 * <ul>
 *   <li>建科目 + 写期初 + code→subjectId + 跳过缺失 + 父级占位 + 同一事务回滚：全部委托
 *       {@link InitialBalanceApplyService#applyToAccountSet}（{@code @Transactional(rollbackFor=Exception)}）；</li>
 *   <li>写期初底层仍是 {@code balanceInitialService.updateInitBalance}（与 {@code /balance/initial} 同源）。</li>
 * </ul>
 * 本控制器只做：回取 XW 缓存 → 借贷平衡门 → 按 {@code confirmed_missing_subject_codes} 过滤要补建的科目 →
 * 组装 {@link InitialBalanceImportResponse} → 调用 apply → 映射 BT 期望的返回结构。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/account-sets/{accountSetsId}/initial-balance")
@SaIgnore
public class OpenApiInitialBalanceController {

    private final InitialBalanceApplyService initialBalanceApplyService;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QAccountSets qAccountSets = QAccountSets.accountSets;
    private static final BigDecimal CENT = new BigDecimal("0.01");

    @Value("${xiaowei.api.base-url:http://localhost:7117}")
    String xiaoweiBaseUrl;

    /** XW 期初解析结果回取路径（GET {base}{path}/{parseTaskId}）。数据不出境：默认 localhost。 */
    @Value("${xiaowei.api.initial-balance-parsed-path:/xiaowei/api/v1/initial-balance/parsed}")
    String parsedPath;

    @Value("${xiaowei.api.timeout-ms:5000}")
    int xiaoweiTimeoutMs;

    // ═══════════════════════════════════════════════════════════════
    // POST /openapi/v1/account-sets/{accountSetsId}/initial-balance/import
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/import")
    public JsonResult importInitialBalance(@PathVariable Long accountSetsId,
                                           @RequestBody ImportReq req) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (req == null || req.getParseTaskId() == null || req.getParseTaskId().isBlank()) {
            return JsonResult.failure("parse_task_id 不能为空");
        }
        AccountSets accountSets = findAccountSet(accountSetsId);
        if (accountSets == null) {
            return JsonResult.failure("账套不存在：" + accountSetsId);
        }

        // 1. 回取 XW 解析缓存（过期 / 取不到 → PARSE_EXPIRED）
        ParsedResult parsed;
        try {
            parsed = fetchParsedResult(req.getParseTaskId());
        } catch (Exception e) {
            log.warn("回取 XW 期初解析缓存失败 parseTaskId={}: {}", req.getParseTaskId(), e.getMessage());
            return failureWithType("解析结果已过期或不可用，请重新解析", "PARSE_EXPIRED");
        }
        if (parsed == null
                || (isEmpty(parsed.balances) && isEmpty(parsed.subjectsToCreate))) {
            return failureWithType("解析结果已过期，请重新解析", "PARSE_EXPIRED");
        }

        // 2. 借贷平衡门（不平且未勾选允许 → IMBALANCE，不写）
        boolean allowImbalance = Boolean.TRUE.equals(req.getAllowImbalance());
        if (!allowImbalance && !isBalanced(parsed.balances)) {
            return failureWithType("试算不平衡，请勾选允许不平或检查 Excel", "IMBALANCE");
        }

        // 3. 仅补建会计确认的缺失科目；未确认的不建（其余额随后在 apply 内按"科目不存在"跳过）
        Set<String> confirmed = new HashSet<>();
        if (req.getConfirmedMissingSubjectCodes() != null) {
            for (String c : req.getConfirmedMissingSubjectCodes()) {
                if (c != null && !c.isBlank()) confirmed.add(c.trim());
            }
        }
        List<InitialBalanceImportResponse.NewSubject> toCreate = new ArrayList<>();
        for (InitialBalanceImportResponse.NewSubject s : orEmpty(parsed.subjectsToCreate)) {
            if (s.getCode() != null && confirmed.contains(s.getCode().trim())) {
                toCreate.add(s);
            }
        }

        // 4. 组装 apply 入参（newSubjects=已确认补建；initialBalances=全量，apply 内解析 code→subjectId、缺失跳过）
        InitialBalanceImportResponse resp = new InitialBalanceImportResponse();
        resp.setSuccess(true);
        InitialBalanceImportResponse.ResponseData data = new InitialBalanceImportResponse.ResponseData();
        data.setNewSubjects(toCreate);
        data.setInitialBalances(orEmpty(parsed.balances));
        data.setPeriod(parsed.period);
        resp.setData(data);

        // 5. 落库（建科目 + 写期初同一事务；失败整体回滚——由 applyToAccountSet 自身 @Transactional 保证）
        InitialBalanceApplyService.ApplyResult result;
        try {
            result = initialBalanceApplyService.applyToAccountSet(accountSets, resp);
        } catch (ServiceException se) {
            // 已结账 / 未设本币 / 建科目失败等：applyToAccountSet 已回滚
            return JsonResult.failure(se.getMessage() != null ? se.getMessage() : "期初导入失败");
        } catch (Exception e) {
            log.error("期初导入落库失败 accountSetsId={} parseTaskId={}", accountSetsId, req.getParseTaskId(), e);
            return JsonResult.failure("期初导入失败：" + e.getMessage());
        }

        // 6. 映射 BT fnSubmitInitialBalanceImport 期望的返回
        List<Map<String, Object>> failures = new ArrayList<>();
        for (String code : orEmptyStr(result.getSkippedBalanceCodes())) {
            failures.add(failure(null, code, "科目不存在或未确认补建，余额已跳过"));
        }
        for (InitialBalanceApplyService.ApplyResult.InvalidEncodingSubject inv
                : orEmptyInvalid(result.getInvalidEncodingNewSubjects())) {
            failures.add(failure(null, inv.getCode(),
                    "编码不符合账套规则（" + inv.getEncodingRule() + "），未补建"));
        }
        int skippedRows = orEmptyStr(result.getSkippedBalanceCodes()).size()
                + orEmptyInvalid(result.getInvalidEncodingNewSubjects()).size();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imported_rows", result.getInitialBalancesCount());
        out.put("created_subjects", result.getNewSubjectsCount());
        out.put("skipped_rows", skippedRows);
        out.put("failures", failures);
        return JsonResult.successful(out);
    }

    /** package-private 钩子：单测覆写以绕过 blaze 查询。 */
    AccountSets findAccountSet(Long accountSetsId) {
        return bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(accountSetsId))
                .fetchFirst();
    }

    // ═══════════════════════════════════════════════════════════════
    // XW 解析缓存回取（package-private，便于单测覆写绕过真实 HTTP）
    // ═══════════════════════════════════════════════════════════════

    /**
     * GET {base-url}{parsedPath}/{parseTaskId} → 解析 {subjects_to_create[], balances[], period, encoding}。
     * 兼容 {@code {data:{...}}} 包裹与顶层裸结构；字段名兼容 snake_case / camelCase。
     * 取不到 / 4xx / 5xx / 超时 → 抛异常（调用方转 PARSE_EXPIRED）。
     */
    ParsedResult fetchParsedResult(String parseTaskId) {
        String base = xiaoweiBaseUrl != null && xiaoweiBaseUrl.endsWith("/")
                ? xiaoweiBaseUrl.substring(0, xiaoweiBaseUrl.length() - 1) : xiaoweiBaseUrl;
        String url = base + parsedPath + "/" + parseTaskId;

        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(xiaoweiTimeoutMs);
        f.setReadTimeout(xiaoweiTimeoutMs);
        RestTemplate rt = new RestTemplate(f);

        ResponseEntity<String> re = rt.getForEntity(url, String.class);
        String body = re.getBody();
        if (body == null || body.isBlank()) {
            return null;
        }
        JSONObject root = JSON.parseObject(body);
        if (root == null) return null;
        // 兼容 {data:{...}} 包裹
        JSONObject d = root.getJSONObject("data");
        if (d == null) d = root;

        ParsedResult pr = new ParsedResult();
        pr.period = firstStr(d, "period");

        pr.subjectsToCreate = new ArrayList<>();
        JSONArray subs = firstArr(d, "subjects_to_create", "subjectsToCreate");
        if (subs != null) {
            for (int i = 0; i < subs.size(); i++) {
                JSONObject o = subs.getJSONObject(i);
                if (o == null) continue;
                InitialBalanceImportResponse.NewSubject ns = new InitialBalanceImportResponse.NewSubject();
                ns.setCode(firstStr(o, "code"));
                ns.setName(firstStr(o, "name"));
                ns.setType(firstStr(o, "type"));
                ns.setBalanceDirection(firstStr(o, "balance_direction", "balanceDirection"));
                ns.setParentCode(firstStr(o, "parent_code", "parentCode"));
                ns.setUnit(firstStr(o, "unit"));
                pr.subjectsToCreate.add(ns);
            }
        }

        pr.balances = new ArrayList<>();
        JSONArray bals = firstArr(d, "balances");
        if (bals != null) {
            for (int i = 0; i < bals.size(); i++) {
                JSONObject o = bals.getJSONObject(i);
                if (o == null) continue;
                InitialBalanceImportResponse.InitialBalance ib = new InitialBalanceImportResponse.InitialBalance();
                ib.setCode(firstStr(o, "code"));
                ib.setBalanceDirection(firstStr(o, "balance_direction", "balanceDirection"));
                ib.setBeginBalance(firstDec(o, "begin_balance", "beginBalance"));
                ib.setYearTotalDebit(firstDec(o, "year_total_debit", "yearTotalDebit"));
                ib.setYearTotalCredit(firstDec(o, "year_total_credit", "yearTotalCredit"));
                ib.setYearTotal(firstDec(o, "year_total", "yearTotal"));
                ib.setBeginQuantity(firstDec(o, "begin_quantity", "beginQuantity"));
                pr.balances.add(ib);
            }
        }
        return pr;
    }

    // ═══════════════════════════════════════════════════════════════
    // 借贷平衡门：按解析行的方向汇总期初（package-private 便于单测）
    // ═══════════════════════════════════════════════════════════════

    static boolean isBalanced(List<InitialBalanceImportResponse.InitialBalance> balances) {
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        if (balances != null) {
            for (InitialBalanceImportResponse.InitialBalance ib : balances) {
                if (ib == null || ib.getCode() == null || ib.getCode().isBlank()) continue;
                BigDecimal amt = ib.getBeginBalance() != null ? ib.getBeginBalance() : BigDecimal.ZERO;
                if (isCredit(ib.getBalanceDirection())) {
                    credit = credit.add(amt);
                } else {
                    debit = debit.add(amt);
                }
            }
        }
        return debit.subtract(credit).abs().compareTo(CENT) <= 0;
    }

    private static boolean isCredit(String dir) {
        return dir != null && dir.contains("贷");
    }

    // ═══════════════════════════════════════════════════════════════
    // 小工具
    // ═══════════════════════════════════════════════════════════════

    private static JsonResult failureWithType(String msg, String errorType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorType", errorType);
        data.put("type", errorType);   // 兼容 BT 读 type / errorType
        return JsonResult.failure(msg).setData(data);
    }

    private static Map<String, Object> failure(Integer row, String subjectCode, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (row != null) m.put("row", row);
        if (subjectCode != null) m.put("subject_code", subjectCode);
        m.put("reason", reason);
        return m;
    }

    private static boolean isEmpty(List<?> l) {
        return l == null || l.isEmpty();
    }

    private static <T> List<T> orEmpty(List<T> l) {
        return l != null ? l : Collections.emptyList();
    }

    private static List<String> orEmptyStr(List<String> l) {
        return l != null ? l : Collections.emptyList();
    }

    private static List<InitialBalanceApplyService.ApplyResult.InvalidEncodingSubject> orEmptyInvalid(
            List<InitialBalanceApplyService.ApplyResult.InvalidEncodingSubject> l) {
        return l != null ? l : Collections.emptyList();
    }

    private static String firstStr(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o.containsKey(k)) {
                String v = o.getString(k);
                if (v != null) return v;
            }
        }
        return null;
    }

    private static BigDecimal firstDec(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o.containsKey(k) && o.get(k) != null) {
                try {
                    return o.getBigDecimal(k);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static JSONArray firstArr(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o.containsKey(k)) {
                JSONArray a = o.getJSONArray(k);
                if (a != null) return a;
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // DTO
    // ═══════════════════════════════════════════════════════════════

    /** XW 解析缓存的规范化结果（FN 侧消费视图）。 */
    static class ParsedResult {
        List<InitialBalanceImportResponse.NewSubject> subjectsToCreate;
        List<InitialBalanceImportResponse.InitialBalance> balances;
        String period;
    }

    @Data
    public static class ImportReq {
        /** XW 解析任务缓存 ID。BT 发 snake_case，@JsonProperty 绑定、@JsonAlias 兼容 camelCase。 */
        @com.fasterxml.jackson.annotation.JsonProperty("parse_task_id")
        @com.fasterxml.jackson.annotation.JsonAlias({"parseTaskId"})
        private String parseTaskId;
        /** 会计确认要补建的缺失科目编码 */
        @com.fasterxml.jackson.annotation.JsonProperty("confirmed_missing_subject_codes")
        @com.fasterxml.jackson.annotation.JsonAlias({"confirmedMissingSubjectCodes"})
        private List<String> confirmedMissingSubjectCodes;
        /** 是否允许借贷不平衡（BT「允许不平」开关） */
        @com.fasterxml.jackson.annotation.JsonProperty("allow_imbalance")
        @com.fasterxml.jackson.annotation.JsonAlias({"allowImbalance"})
        private Boolean allowImbalance;
        /** 操作人（审计，可选） */
        @com.fasterxml.jackson.annotation.JsonProperty("updated_by")
        @com.fasterxml.jackson.annotation.JsonAlias({"updatedBy"})
        private String updatedBy;
    }
}
