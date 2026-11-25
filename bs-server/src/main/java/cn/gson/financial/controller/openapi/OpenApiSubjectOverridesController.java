package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSetSubjectOverride;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.repo.AccountSetSubjectOverrideRepository;
import cn.gson.financial.kernel.service.SubjectService;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 开放 API — 账套科目覆写（FN-NEW-CARRYOVER-OVERRIDES-PERSISTENCE）。
 * <p>
 * 路径前缀 {@code /openapi/v1/account-sets/{id}/subject-overrides}。
 * XW 结转 / preflight 阶段优先用本表的账套级覆写，兜底走默认科目匹配。
 * BT 编辑器后续会通过 PUT 接口写回。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/account-sets/{accountSetsId}/subject-overrides")
@SaIgnore
public class OpenApiSubjectOverridesController {

    /**
     * FN-NEW-CONFIG-INIT-SOURCE-FIELD：4 个 source 白名单，与 AccountSetProfile 同语义。
     * <p>优先级：accountant_custom &gt; accountant_reviewed &gt; system_suggested &gt; auto_matched
     */
    static final String SOURCE_AUTO_MATCHED = "auto_matched";
    static final String SOURCE_ACCOUNTANT_CUSTOM = "accountant_custom";
    static final String SOURCE_ACCOUNTANT_REVIEWED = "accountant_reviewed";
    static final String SOURCE_SYSTEM_SUGGESTED = "system_suggested";
    static final String SOURCE_DEFAULT = SOURCE_ACCOUNTANT_CUSTOM;
    static final Set<String> ALLOWED_SOURCES = Set.of(
            SOURCE_AUTO_MATCHED, SOURCE_ACCOUNTANT_CUSTOM, SOURCE_ACCOUNTANT_REVIEWED, SOURCE_SYSTEM_SUGGESTED
    );

    private final AccountSetSubjectOverrideRepository overrideRepository;
    private final SubjectService subjectService;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QAccountSets qAccountSets = QAccountSets.accountSets;

    // ═══════════════════════════════════════════════════════════════
    // GET — 拉账套已配的覆写清单
    // ═══════════════════════════════════════════════════════════════

    @GetMapping
    public JsonResult list(@PathVariable Long accountSetsId) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (!accountSetExists(accountSetsId)) {
            return JsonResult.failure("账套不存在：" + accountSetsId);
        }

        List<AccountSetSubjectOverride> rows = overrideRepository.findByAccountSetsId(accountSetsId);
        List<Map<String, Object>> data = new ArrayList<>(rows.size());
        for (AccountSetSubjectOverride o : rows) {
            data.add(toMap(o));
        }
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // PUT — 批量 upsert（按 bizMeaning 合并 / 新建）
    //   入参体：[{ "bizMeaning": "主营业务收入", "subjectId": 12345 }, ...]
    // ═══════════════════════════════════════════════════════════════

    @PutMapping
    @Transactional
    public JsonResult upsert(@PathVariable Long accountSetsId,
                             @RequestBody List<OverrideReq> reqs) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (reqs == null || reqs.isEmpty()) {
            return JsonResult.failure("覆写列表不能为空");
        }
        if (!accountSetExists(accountSetsId)) {
            return JsonResult.failure("账套不存在：" + accountSetsId);
        }

        // 预校验：每条都要含 bizMeaning + subjectId，且 subjectId 在该账套真实存在
        for (OverrideReq r : reqs) {
            if (r == null || r.getBizMeaning() == null || r.getBizMeaning().isBlank()) {
                return JsonResult.failure("bizMeaning 不能为空");
            }
            if (r.getSubjectId() == null) {
                return JsonResult.failure("subjectId 不能为空：bizMeaning=" + r.getBizMeaning());
            }
            Subject subject = subjectService.findById(accountSetsId, r.getSubjectId());
            if (subject == null) {
                return JsonResult.failure("科目不存在：subjectId=" + r.getSubjectId()
                        + " bizMeaning=" + r.getBizMeaning());
            }
            // FN-NEW-CONFIG-INIT-SOURCE-FIELD: source 白名单校验（null 时兜底 accountant_custom）
            if (r.getSource() != null && !ALLOWED_SOURCES.contains(r.getSource())) {
                return JsonResult.failure("source 取值非法：" + r.getSource()
                        + "，允许：" + ALLOWED_SOURCES);
            }
        }

        // upsert：按 (accountSetsId, bizMeaning) 找已有，存在则更新、不存在则新建
        LocalDateTime now = LocalDateTime.now();
        int updated = 0;
        int created = 0;
        List<Map<String, Object>> resultRows = new ArrayList<>(reqs.size());

        for (OverrideReq r : reqs) {
            Subject subject = subjectService.findById(accountSetsId, r.getSubjectId());
            Optional<AccountSetSubjectOverride> existingOpt =
                    overrideRepository.findByAccountSetsIdAndBizMeaning(accountSetsId, r.getBizMeaning());

            AccountSetSubjectOverride entity;
            if (existingOpt.isPresent()) {
                entity = existingOpt.get();
                entity.setSubjectId(r.getSubjectId());
                entity.setSubjectCode(subject.getCode());
                entity.setSubjectName(subject.getFullName());
                entity.setUpdatedBy(r.getUpdatedBy());
                entity.setSource(r.getSource() == null ? SOURCE_DEFAULT : r.getSource());
                entity.setUpdatedAt(now);
                updated++;
            } else {
                entity = new AccountSetSubjectOverride();
                entity.setAccountSetsId(accountSetsId);
                entity.setBizMeaning(r.getBizMeaning());
                entity.setSubjectId(r.getSubjectId());
                entity.setSubjectCode(subject.getCode());
                entity.setSubjectName(subject.getFullName());
                entity.setUpdatedBy(r.getUpdatedBy());
                entity.setSource(r.getSource() == null ? SOURCE_DEFAULT : r.getSource());
                entity.setCreatedAt(now);
                entity.setUpdatedAt(now);
                created++;
            }
            AccountSetSubjectOverride saved = overrideRepository.save(entity);
            resultRows.add(toMap(saved));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("created", created);
        data.put("updated", updated);
        data.put("rows", resultRows);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // DELETE — 删除某项覆写（回落到默认匹配）
    // ═══════════════════════════════════════════════════════════════

    @DeleteMapping("/{bizMeaning}")
    @Transactional
    public JsonResult delete(@PathVariable Long accountSetsId,
                             @PathVariable String bizMeaning) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (bizMeaning == null || bizMeaning.isBlank()) {
            return JsonResult.failure("bizMeaning 不能为空");
        }
        if (!accountSetExists(accountSetsId)) {
            return JsonResult.failure("账套不存在：" + accountSetsId);
        }

        long deleted = overrideRepository.deleteByAccountSetsIdAndBizMeaning(accountSetsId, bizMeaning);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted", deleted);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════════

    private Map<String, Object> toMap(AccountSetSubjectOverride o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("accountSetsId", o.getAccountSetsId());
        m.put("bizMeaning", o.getBizMeaning());
        m.put("subjectId", o.getSubjectId());
        m.put("subjectCode", o.getSubjectCode());
        m.put("subjectName", o.getSubjectName());
        m.put("updatedBy", o.getUpdatedBy());
        m.put("source", o.getSource());
        m.put("createdAt", o.getCreatedAt());
        m.put("updatedAt", o.getUpdatedAt());
        return m;
    }

    /** package-private test hook：单测覆写避开 BlazeJPAQuery 直查 */
    boolean accountSetExists(Long accountSetsId) {
        return bqf.selectFrom(qAccountSets).select(qAccountSets.id)
                .where(qAccountSets.id.eq(accountSetsId))
                .fetchFirst() != null;
    }

    @Data
    public static class OverrideReq {
        private String bizMeaning;
        private Long subjectId;
        /** 可选：BT 端写回时带操作员；OpenAPI 匿名调用为 null */
        private String updatedBy;
        /**
         * FN-NEW-CONFIG-INIT-SOURCE-FIELD：来源标记，可选。
         * 不传时按 {@code accountant_custom} 落盘。
         * 三值：{@code auto_matched / accountant_custom / accountant_reviewed}。
         */
        private String source;
    }
}
