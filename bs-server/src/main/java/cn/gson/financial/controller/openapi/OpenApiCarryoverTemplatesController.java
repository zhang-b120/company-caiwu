package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSetProfile;
import cn.gson.financial.kernel.model.entity.AccountSetSubjectOverride;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.entity.QSubject;
import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.repo.AccountSetProfileRepository;
import cn.gson.financial.kernel.model.repo.AccountSetSubjectOverrideRepository;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 开放 API — 结转模板（V5 简化版）。
 *
 * <p><b>V5 关键变化</b> vs V4：
 * <ul>
 *   <li>13 张默认模板 → 6 张（pl_carry / cost_carry / surtax / vat_settle / income_tax / year_profit_carry）</li>
 *   <li>SUBJECT_KEYWORDS_17 → SUBJECT_KEYWORDS_V5（26 项预设 code，含父级 + 3 项新税金）</li>
 *   <li>entries 加 standard_code / formula_description / expand_leaf_with_balance 三字段</li>
 *   <li>init 改为本地预设直查 leaf（去掉 xiaowei /match-subjects HTTP 依赖，V4 留的 TODO 直接废弃）</li>
 *   <li>vat_settle 按账套 taxpayer_type（VatType.SMALL_SCALE / COMMONLY）切两套 entries</li>
 *   <li>父级展开：5301/5601/5602/5603/5711 init 时扫描父级下所有 leaf 各生成一条 entry</li>
 *   <li>force=true 允许覆盖 accountant_custom 的 entries（重置回默认）</li>
 *   <li>响应扩 schemaVersion=v5 / taxpayerType / templateVersion / expansionSummary / overriddenCustomCount</li>
 * </ul>
 *
 * <p>3 个端点（path 不变）：
 * <ul>
 *   <li>GET → 完整 6 张含 entries[]（合并默认 + 账套覆写）</li>
 *   <li>PUT → 批量改 entries[]；每个 entry.subjectId 必须 leaf=true（否则 400 SUBJECT_NOT_LEAF）</li>
 *   <li>POST /init → 拉 leaf_subjects → 本地 26 项 + 父级展开 → 拼出 6 张 entries → INSERT</li>
 * </ul>
 *
 * <p>单一真相源：{@code 项目工单\32_结转模板V5_预设科目映射.md}（用户 2026-05-28 提供）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/account-sets/{accountSetsId}/carryover-templates")
@SaIgnore
public class OpenApiCarryoverTemplatesController {

    static final String SECTION_TYPE_CARRYOVER = "carryover_rule";

    /** 4 类 template_kind 白名单（V5 沿用 V4） */
    static final String KIND_AMOUNT_ONLY = "amount_only";
    static final String KIND_MULTI_OUTPUT = "multi_output";
    static final String KIND_BALANCE_CLEAR = "balance_clear";
    static final String KIND_VAT_SETTLE = "vat_settle";
    static final Set<String> ALLOWED_TEMPLATE_KINDS = Set.of(
            KIND_AMOUNT_ONLY, KIND_MULTI_OUTPUT, KIND_BALANCE_CLEAR, KIND_VAT_SETTLE);

    /** entry.direction 白名单：V5 新增 balance（pl_carry 末行借贷差额）*/
    static final String DIR_DEBIT = "debit";
    static final String DIR_CREDIT = "credit";
    static final String DIR_BALANCE = "balance";
    static final Set<String> ALLOWED_DIRECTIONS = Set.of(DIR_DEBIT, DIR_CREDIT, DIR_BALANCE);

    /** entry.source 白名单（V5 把 V4 rule_matched 改名 preset_matched，含义相同） */
    static final String ENTRY_SRC_PRESET_MATCHED = "preset_matched";
    static final String ENTRY_SRC_LLM_MATCHED = "llm_matched";
    static final String ENTRY_SRC_ACCOUNTANT_CUSTOM = "accountant_custom";
    static final String ENTRY_SRC_MISSING = "missing";
    static final Set<String> ALLOWED_ENTRY_SOURCES = Set.of(
            ENTRY_SRC_PRESET_MATCHED, ENTRY_SRC_LLM_MATCHED, ENTRY_SRC_ACCOUNTANT_CUSTOM, ENTRY_SRC_MISSING);

    /** 4 个 profile-level source（保持向后兼容） */
    static final String SOURCE_AUTO_MATCHED = "auto_matched";
    static final String SOURCE_ACCOUNTANT_CUSTOM = "accountant_custom";
    static final String SOURCE_ACCOUNTANT_REVIEWED = "accountant_reviewed";
    static final String SOURCE_SYSTEM_SUGGESTED = "system_suggested";
    static final Set<String> ALLOWED_SOURCES = Set.of(
            SOURCE_AUTO_MATCHED, SOURCE_ACCOUNTANT_CUSTOM, SOURCE_ACCOUNTANT_REVIEWED, SOURCE_SYSTEM_SUGGESTED);

    /** amount_dsl 最大长度（V5 沿用） */
    static final int AMOUNT_DSL_MAX_LENGTH = 2048;

    /** rules JSON 字段名 */
    static final String FIELD_TEMPLATE_KIND = "template_kind";
    static final String FIELD_VOUCHER_SUMMARY = "voucher_summary";
    static final String FIELD_ENTRIES = "entries";
    static final String FIELD_FORMULA_DSL_V3 = "formula_dsl";   // V3 兼容字段

    /** taxpayer_type 取值（V5 init 响应字段） */
    static final String TP_GENERAL = "general";
    static final String TP_SMALL_SCALE = "small_scale";

    /** 5 个父级 code，init 时扫描 leaf 子科目逐一展开 entry */
    static final Set<String> PARENT_CODES_TO_EXPAND = Set.of("5301", "5601", "5602", "5603", "5711");

    /**
     * V5 26 项业务含义 → 精确科目 code 映射（单一真相源：项目工单\32_结转模板V5_预设科目映射.md）。
     * 含父级（5 项 5301/5601/5602/5603/5711 — init 时展开 leaf）+ 23 项 leaf + 1 项小规模专用 2221026。
     */
    static final List<SubjectKeywordRule> SUBJECT_KEYWORDS_V5 = List.of(
            new SubjectKeywordRule("主营业务收入", "5001"),
            new SubjectKeywordRule("其他业务收入", "5051"),
            new SubjectKeywordRule("营业外收入", "5301"),               // 父级，需展开
            new SubjectKeywordRule("主营业务成本", "5401"),
            new SubjectKeywordRule("其他业务成本", "5402"),
            new SubjectKeywordRule("税金及附加", "5403"),
            new SubjectKeywordRule("销售费用", "5601"),                 // 父级，需展开
            new SubjectKeywordRule("管理费用", "5602"),                 // 父级，需展开
            new SubjectKeywordRule("财务费用", "5603"),                 // 父级，需展开
            new SubjectKeywordRule("营业外支出", "5711"),               // 父级，需展开
            new SubjectKeywordRule("所得税费用", "5801"),
            new SubjectKeywordRule("库存商品", "1405"),
            new SubjectKeywordRule("销项税额", "2221001005"),
            new SubjectKeywordRule("进项税额", "2221001001"),
            new SubjectKeywordRule("进项税额转出", "2221001007"),
            new SubjectKeywordRule("转出未交增值税", "2221001003"),
            new SubjectKeywordRule("未交增值税", "2221002"),
            new SubjectKeywordRule("应交所得税", "2221006"),
            new SubjectKeywordRule("应交城建税", "2221008"),
            new SubjectKeywordRule("应交房产税", "2221009"),
            new SubjectKeywordRule("应交城镇土地使用税", "2221010"),
            new SubjectKeywordRule("教育费附加", "2221013"),
            new SubjectKeywordRule("地方教育费附加", "2221014"),
            new SubjectKeywordRule("印花税", "2221017"),
            new SubjectKeywordRule("本年利润", "3103"),
            new SubjectKeywordRule("未分配利润", "3104001"),
            new SubjectKeywordRule("应交增值税(小规模专用)", "2221026")     // 仅 vat_settle 小规模版本用
    );

    /**
     * 6 张系统默认模板（V5）。pl_carry 含 expand_leaf_with_balance 占位；
     * vat_settle 同时含 general / small_scale 两套 entries，init 时按账套 taxpayer_type 选其一。
     */
    static final List<DefaultCarryoverTemplate> DEFAULT_TEMPLATES = buildDefaultTemplates();

    static final Map<String, DefaultCarryoverTemplate> DEFAULT_BY_KEY;
    static {
        Map<String, DefaultCarryoverTemplate> m = new LinkedHashMap<>();
        for (DefaultCarryoverTemplate t : DEFAULT_TEMPLATES) m.put(t.key, t);
        DEFAULT_BY_KEY = Map.copyOf(m);
    }

    private final AccountSetProfileRepository profileRepository;
    private final AccountSetSubjectOverrideRepository subjectOverrideRepository;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    /** xiaowei skill 服务基础地址（V4 遗留，V5 init 主路径不再调；保留方法为 future 工单） */
    @org.springframework.beans.factory.annotation.Value("${xiaowei.api.base-url:http://localhost:7117}")
    String xiaoweiBaseUrl;

    @org.springframework.beans.factory.annotation.Value("${xiaowei.api.match-subjects-path:/xiaowei/api/v1/carryover/match-subjects}")
    String xiaoweiMatchSubjectsPath;

    @org.springframework.beans.factory.annotation.Value("${xiaowei.api.timeout-ms:5000}")
    int xiaoweiTimeoutMs;

    private static final QAccountSets qAccountSets = QAccountSets.accountSets;
    private static final QSubject qSubject = QSubject.subject;

    // ═══════════════════════════════════════════════════════════════
    // GET — 合并系统默认 + 账套覆写返完整 6 张
    // ═══════════════════════════════════════════════════════════════

    @GetMapping
    public JsonResult list(@PathVariable Long accountSetsId) {
        if (accountSetsId == null) return JsonResult.failure("accountSetsId 不能为空");
        if (!accountSetExists(accountSetsId)) return JsonResult.failure("账套不存在：" + accountSetsId);

        Map<String, AccountSetProfile> overrideByKey = new HashMap<>();
        // FN-FIX-CARRYOVER-V5-E2E-TEST-AND-CLEANUP：检测残留 V4 旧 key（非 V5 6 项白名单的 carryover_rule 数据）
        List<String> legacyV4Keys = new ArrayList<>();
        for (AccountSetProfile p : profileRepository.findByAccountSetsIdOrderByPriorityDescIdAsc(accountSetsId)) {
            if (!SECTION_TYPE_CARRYOVER.equals(p.getSectionType())) continue;
            if (!Boolean.TRUE.equals(p.getEnabled())) continue;
            if (p.getSectionKey() == null) continue;
            String key = p.getSectionKey();
            if (DEFAULT_BY_KEY.containsKey(key)) {
                overrideByKey.put(key, p);
            } else {
                legacyV4Keys.add(key);
            }
        }

        List<Map<String, Object>> templates = new ArrayList<>(DEFAULT_TEMPLATES.size());
        for (DefaultCarryoverTemplate def : DEFAULT_TEMPLATES) {
            templates.add(mergeOne(def, overrideByKey.get(def.key)));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountSetsId", String.valueOf(accountSetsId));
        data.put("total", templates.size());
        data.put("templates", templates);
        data.put("schemaVersion", "v5");
        // 仅当真有 V4 残留时透出（前端可不渲染该字段就当无残留处理）
        if (!legacyV4Keys.isEmpty()) {
            data.put("legacy_v4_keys_detected", legacyV4Keys);
        }
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // PUT — 批量改 entries[]，每个 entry.subjectId 必须 leaf=true
    // ═══════════════════════════════════════════════════════════════

    @PutMapping
    @Transactional
    public JsonResult upsert(@PathVariable Long accountSetsId,
                             @RequestBody List<TemplateReq> reqs) {
        if (accountSetsId == null) return JsonResult.failure("accountSetsId 不能为空");
        if (reqs == null || reqs.isEmpty()) return JsonResult.failure("结转模板列表不能为空");
        if (!accountSetExists(accountSetsId)) return JsonResult.failure("账套不存在：" + accountSetsId);

        Set<Long> allSubjectIds = new HashSet<>();
        for (TemplateReq r : reqs) {
            String err = validateTemplateReq(r, allSubjectIds);
            if (err != null) return JsonResult.failure(err);
        }
        Map<Long, Subject> subjectByIdLeaf = loadLeafSubjectsByIds(accountSetsId, allSubjectIds);
        for (TemplateReq r : reqs) {
            for (EntryReq e : r.getEntries()) {
                if (e.getSubjectId() == null) continue;
                Long sid = parseSubjectIdSafe(e.getSubjectId());
                if (sid == null) {
                    return failureWithType("entry.subjectId 不是合法 long: " + e.getSubjectId(), "SUBJECT_ID_INVALID");
                }
                Subject s = subjectByIdLeaf.get(sid);
                if (s == null) {
                    return failureWithType("entry.subjectId 不在该账套或不是末级（leaf=true）：subjectId="
                            + e.getSubjectId() + " key=" + r.getKey() + " biz=" + e.getBizMeaning(),
                            "SUBJECT_NOT_LEAF");
                }
            }
        }

        LocalDateTime now = LocalDateTime.now();
        int created = 0, updated = 0;
        List<Map<String, Object>> rows = new ArrayList<>(reqs.size());

        for (TemplateReq r : reqs) {
            DefaultCarryoverTemplate def = DEFAULT_BY_KEY.get(r.getKey());
            Optional<AccountSetProfile> existing = profileRepository
                    .findByAccountSetsIdAndSectionTypeAndSectionKey(
                            accountSetsId, SECTION_TYPE_CARRYOVER, r.getKey());
            AccountSetProfile entity;
            if (existing.isPresent()) {
                entity = existing.get();
                updated++;
            } else {
                entity = new AccountSetProfile();
                entity.setAccountSetsId(accountSetsId);
                entity.setSectionType(SECTION_TYPE_CARRYOVER);
                entity.setSectionKey(r.getKey());
                entity.setCreatedAt(now);
                created++;
            }
            entity.setTitle(def.nameCn);
            entity.setRules(buildRulesJson(r, subjectByIdLeaf));
            entity.setPriority(0);
            entity.setEnabled(Boolean.TRUE);
            entity.setSource(r.getSource() == null ? SOURCE_ACCOUNTANT_CUSTOM : r.getSource());
            entity.setUpdatedBy(r.getUpdatedBy());
            entity.setUpdatedAt(now);
            profileRepository.save(entity);
            rows.add(mergeOne(def, entity));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("created", created);
        data.put("updated", updated);
        data.put("rows", rows);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // POST /init — V5 本地预设直查：
    //   ① 读账套 taxpayer_type（VatType.SMALL_SCALE → small_scale，COMMONLY → general）
    //   ② 拉 leaf_subjects
    //   ③ 26 项 SUBJECT_KEYWORDS_V5 在 leaf 表精确命中 code（5 个父级跳过，由父级展开处理）
    //   ④ 6 张模板 entries 填 subjectId；遇 expand_leaf_with_balance=true 用所有 leaf 子科目展开
    //   ⑤ vat_settle 按 taxpayer_type 选 general 或 small_scale entries
    //   ⑥ force=true 时允许覆盖 source=accountant_custom 的 profile
    //   ⑦ 响应扩 expansionSummary / taxpayerType / templateVersion / overriddenCustomCount
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/init")
    @Transactional
    public JsonResult init(@PathVariable Long accountSetsId,
                           @RequestBody(required = false) InitReq req,
                           @RequestParam(value = "force", required = false, defaultValue = "false") boolean forceParam) {
        if (accountSetsId == null) return JsonResult.failure("accountSetsId 不能为空");
        if (!accountSetExists(accountSetsId)) return JsonResult.failure("账套不存在：" + accountSetsId);
        boolean overrideExisting = req != null && Boolean.TRUE.equals(req.getOverrideExisting());
        boolean force = forceParam || (req != null && Boolean.TRUE.equals(req.getForce()));

        LocalDateTime now = LocalDateTime.now();

        // ─── 1. taxpayer_type 派生（account_sets.vat_type 字段） ─────
        String taxpayerType = loadTaxpayerTypeForTest(accountSetsId);
        String templateVersion = "v5-" + taxpayerType;

        // ─── 2. 拉账套全部科目（含 leaf=0，Level 2/3 回退要看到 leaf=0 的行）───
        List<Subject> allSubjects = loadAllSubjectsForTest(accountSetsId);
        Map<String, Subject> byCode = new HashMap<>();
        List<Subject> leafSubjects = new ArrayList<>();
        for (Subject s : allSubjects) {
            if (s.getCode() != null) byCode.put(s.getCode(), s);
            if (Boolean.TRUE.equals(s.getLeaf())) leafSubjects.add(s);
        }
        // 父级 → 该父级下所有 leaf 列表（按 code 前缀匹配），给静态 PARENT_CODES_TO_EXPAND 用
        Map<String, List<Subject>> leavesByParentCode = new HashMap<>();
        for (String parentCode : PARENT_CODES_TO_EXPAND) {
            List<Subject> leaves = leafSubjects.stream()
                    .filter(s -> s.getCode() != null && s.getCode().startsWith(parentCode)
                            && !s.getCode().equals(parentCode))
                    .sorted(Comparator.comparing(Subject::getCode))
                    .collect(Collectors.toList());
            leavesByParentCode.put(parentCode, leaves);
        }

        // ─── 3. SUBJECT_KEYWORDS_V5 走 4 级回退链匹配 → bizToResult ──
        // FN-FIX-CARRYOVER-V5-MATCH-FALLBACK-CHAIN：levels 1/2/3/4 都视为成功；level 5 (missing) 入 subjectsMissing
        Map<String, MatchResult> bizToResult = new LinkedHashMap<>();
        List<Map<String, Object>> subjectsMissing = new ArrayList<>();
        for (SubjectKeywordRule rule : SUBJECT_KEYWORDS_V5) {
            // 父级 5 项（5301/5601/5602/5603/5711）跳过 — 由静态 PARENT_CODES_TO_EXPAND 路径处理
            if (PARENT_CODES_TO_EXPAND.contains(rule.codePrefix)) {
                continue;
            }
            MatchResult result = matchWithFallback(rule.codePrefix, allSubjects, byCode);
            bizToResult.put(rule.bizMeaning, result);
            if (!result.isMatched()) {
                Map<String, Object> miss = new LinkedHashMap<>();
                miss.put("bizMeaning", rule.bizMeaning);
                miss.put("standardCode", rule.codePrefix);
                miss.put("reason", "all_fallback_levels_failed");
                subjectsMissing.add(miss);
            }
        }

        // ─── 4. 同步 subject_overrides（仅 levels 1/3/4 单 subject 命中；level 2 expanded 跳过 — 多 leaf 装不下单条 override）──
        int subjectsCreated = 0, subjectsUpdated = 0, subjectsSkipped = 0;
        for (Map.Entry<String, MatchResult> ent : bizToResult.entrySet()) {
            String biz = ent.getKey();
            MatchResult result = ent.getValue();
            if (!result.isMatched() || result.isExpanded()) continue;
            Subject matchedSubject = result.subject;
            Optional<AccountSetSubjectOverride> existing =
                    subjectOverrideRepository.findByAccountSetsIdAndBizMeaning(accountSetsId, biz);
            if (existing.isPresent() && !force) {
                String src = existing.get().getSource();
                if (SOURCE_ACCOUNTANT_CUSTOM.equals(src) || SOURCE_ACCOUNTANT_REVIEWED.equals(src)) {
                    subjectsSkipped++;
                    continue;
                }
            }
            AccountSetSubjectOverride entity;
            if (existing.isPresent()) {
                entity = existing.get();
                subjectsUpdated++;
            } else {
                entity = new AccountSetSubjectOverride();
                entity.setAccountSetsId(accountSetsId);
                entity.setBizMeaning(biz);
                entity.setCreatedAt(now);
                subjectsCreated++;
            }
            entity.setSubjectId(matchedSubject.getId());
            entity.setSubjectCode(matchedSubject.getCode());
            entity.setSubjectName(matchedSubject.getFullName());
            entity.setSource(SOURCE_AUTO_MATCHED);
            entity.setUpdatedAt(now);
            subjectOverrideRepository.save(entity);
        }

        // 先一次性算 expansionSummary（即使所有模板都 skipped，BT 也能拿到展开情况）
        Map<String, Map<String, Object>> expansionSummary = new LinkedHashMap<>();
        for (String parentCode : PARENT_CODES_TO_EXPAND) {
            List<Subject> leaves = leavesByParentCode.getOrDefault(parentCode, List.of());
            Map<String, Object> sum = new LinkedHashMap<>();
            sum.put("standardCode", parentCode);
            sum.put("leafCount", leaves.size());
            sum.put("expandedToEntries", leaves.size());
            expansionSummary.put(parentCode, sum);
        }

        // ─── 5. 写 6 张 profile 模板（vat_settle 选 taxpayer 分支 + 父级展开）─
        // 跳过策略（V5）：
        //   - force=false（默认）：existing.source ∈ {accountant_custom, accountant_reviewed} → SKIP；
        //                          其余（auto_matched / system_suggested / null）→ UPDATE
        //   - force=true：全 UPDATE；accountant_custom / accountant_reviewed 计入 overriddenCustomCount
        int templatesCreated = 0, templatesUpdated = 0, templatesSkipped = 0, overriddenCustomCount = 0;
        List<Map<String, Object>> templateRows = new ArrayList<>(DEFAULT_TEMPLATES.size());

        for (DefaultCarryoverTemplate def : DEFAULT_TEMPLATES) {
            Optional<AccountSetProfile> existing = profileRepository
                    .findByAccountSetsIdAndSectionTypeAndSectionKey(
                            accountSetsId, SECTION_TYPE_CARRYOVER, def.key);

            AccountSetProfile entity;
            if (existing.isPresent()) {
                AccountSetProfile cur = existing.get();
                String curSrc = cur.getSource();
                boolean isAccountantCustom = SOURCE_ACCOUNTANT_CUSTOM.equals(curSrc)
                        || SOURCE_ACCOUNTANT_REVIEWED.equals(curSrc);
                if (isAccountantCustom && !force) {
                    templatesSkipped++;
                    templateRows.add(mergeOne(def, cur));
                    continue;
                }
                if (isAccountantCustom && force) {
                    overriddenCustomCount++;
                }
                entity = cur;
                templatesUpdated++;
            } else {
                entity = new AccountSetProfile();
                entity.setAccountSetsId(accountSetsId);
                entity.setSectionType(SECTION_TYPE_CARRYOVER);
                entity.setSectionKey(def.key);
                entity.setCreatedAt(now);
                templatesCreated++;
            }
            entity.setTitle(def.nameCn);
            entity.setRules(buildInitRulesJsonV5(def, bizToResult, leavesByParentCode, taxpayerType));
            entity.setPriority(0);
            entity.setEnabled(Boolean.TRUE);
            entity.setSource(SOURCE_AUTO_MATCHED);
            entity.setUpdatedAt(now);
            profileRepository.save(entity);
            templateRows.add(mergeOne(def, entity));
        }

        Map<String, Object> templatesSummary = new LinkedHashMap<>();
        templatesSummary.put("total", DEFAULT_TEMPLATES.size());
        templatesSummary.put("created", templatesCreated);
        templatesSummary.put("updated", templatesUpdated);
        templatesSummary.put("skipped", templatesSkipped);

        Map<String, Object> subjectsSummary = new LinkedHashMap<>();
        // total 用「除 5 个父级外的 21 项」+ 1 项 vat 小规模专用 = 22 计入；vat 一般纳税人专用 1 项视情况
        int expectedLeafCount = (int) SUBJECT_KEYWORDS_V5.stream()
                .filter(r -> !PARENT_CODES_TO_EXPAND.contains(r.codePrefix))
                .count();
        subjectsSummary.put("total", expectedLeafCount);
        long matchedCount = bizToResult.values().stream().filter(MatchResult::isMatched).count();
        subjectsSummary.put("matched", matchedCount);
        subjectsSummary.put("created", subjectsCreated);
        subjectsSummary.put("updated", subjectsUpdated);
        subjectsSummary.put("skipped", subjectsSkipped);
        subjectsSummary.put("missing", subjectsMissing.size());
        subjectsSummary.put("missingDetails", subjectsMissing);

        Map<String, Object> initSummary = new LinkedHashMap<>();
        initSummary.put("source", "local_preset_v5");
        initSummary.put("degraded", false);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountSetsId", String.valueOf(accountSetsId));
        data.put("schemaVersion", "v5");
        data.put("taxpayerType", taxpayerType);
        data.put("templateVersion", templateVersion);
        data.put("vatBranch", TP_SMALL_SCALE.equals(taxpayerType) ? "小规模" : "一般纳税人");
        data.put("force", force);
        data.put("overriddenCustomCount", overriddenCustomCount);
        data.put("expansionSummary", expansionSummary);
        data.put("initSummary", initSummary);
        data.put("templates", templatesSummary);
        data.put("subjects", subjectsSummary);
        data.put("rows", templateRows);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════════

    String validateTemplateReq(TemplateReq r, Set<Long> allSubjectIds) {
        if (r == null) return "结转模板项不能为 null";
        if (r.getKey() == null || r.getKey().isBlank()) return "key 不能为空";
        if (!DEFAULT_BY_KEY.containsKey(r.getKey())) {
            return "key 不在系统默认 6 张模板白名单：" + r.getKey()
                    + "，允许：" + DEFAULT_BY_KEY.keySet();
        }
        if (r.getTemplateKind() == null || r.getTemplateKind().isBlank()) {
            return "templateKind 不能为空：key=" + r.getKey();
        }
        if (!ALLOWED_TEMPLATE_KINDS.contains(r.getTemplateKind())) {
            return "templateKind 取值非法：" + r.getTemplateKind() + "，允许：" + ALLOWED_TEMPLATE_KINDS;
        }
        if (r.getSource() != null && !ALLOWED_SOURCES.contains(r.getSource())) {
            return "source 取值非法：" + r.getSource() + "，允许：" + ALLOWED_SOURCES;
        }
        if (r.getEntries() == null || r.getEntries().isEmpty()) {
            return "entries 不能为空：key=" + r.getKey();
        }
        for (EntryReq e : r.getEntries()) {
            if (e == null) return "entry 不能为 null：key=" + r.getKey();
            if (e.getDirection() == null || !ALLOWED_DIRECTIONS.contains(e.getDirection())) {
                return "entry.direction 取值非法：" + e.getDirection() + "（必须 debit/credit/balance）key=" + r.getKey();
            }
            if (e.getBizMeaning() == null || e.getBizMeaning().isBlank()) {
                return "entry.bizMeaning 不能为空：key=" + r.getKey();
            }
            if (e.getSource() != null && !ALLOWED_ENTRY_SOURCES.contains(e.getSource())) {
                return "entry.source 取值非法：" + e.getSource() + "，允许：" + ALLOWED_ENTRY_SOURCES;
            }
            if (e.getAmountDsl() != null && e.getAmountDsl().length() > AMOUNT_DSL_MAX_LENGTH) {
                return "entry.amountDsl 长度超 " + AMOUNT_DSL_MAX_LENGTH + "：key=" + r.getKey()
                        + " biz=" + e.getBizMeaning();
            }
            if (e.getSubjectId() != null && !e.getSubjectId().isBlank()) {
                Long sid = parseSubjectIdSafe(e.getSubjectId());
                if (sid == null) {
                    return "entry.subjectId 不是合法 long：" + e.getSubjectId()
                            + " key=" + r.getKey() + " biz=" + e.getBizMeaning();
                }
                allSubjectIds.add(sid);
            }
            if ((e.getSubjectId() == null || e.getSubjectId().isBlank())
                    && !ENTRY_SRC_MISSING.equals(e.getSource())) {
                return "entry.subjectId 为空时 entry.source 必须为 missing：key=" + r.getKey()
                        + " biz=" + e.getBizMeaning();
            }
        }
        return null;
    }

    String buildRulesJson(TemplateReq r, Map<Long, Subject> subjectByIdLeaf) {
        JSONObject json = new JSONObject(true);
        json.put(FIELD_TEMPLATE_KIND, r.getTemplateKind());
        if (r.getVoucherSummary() != null) json.put(FIELD_VOUCHER_SUMMARY, r.getVoucherSummary());

        JSONArray entries = new JSONArray();
        for (EntryReq e : r.getEntries()) {
            JSONObject je = new JSONObject(true);
            je.put("direction", e.getDirection());
            je.put("biz_meaning", e.getBizMeaning());
            je.put("standard_code", e.getStandardCode());
            je.put("subject_id", e.getSubjectId());
            if (e.getSubjectId() != null && !e.getSubjectId().isBlank()) {
                Long sid = parseSubjectIdSafe(e.getSubjectId());
                if (sid != null) {
                    Subject s = subjectByIdLeaf.get(sid);
                    if (s != null) {
                        je.put("subject_code", s.getCode());
                        je.put("subject_name", s.getFullName());
                    }
                }
            } else {
                je.put("subject_code", null);
                je.put("subject_name", null);
            }
            je.put("entry_summary", e.getEntrySummary());
            je.put("amount_dsl", e.getAmountDsl());
            if (e.getFormulaDescription() != null) je.put("formula_description", e.getFormulaDescription());
            je.put("source", e.getSource() != null ? e.getSource() : ENTRY_SRC_ACCOUNTANT_CUSTOM);
            if (e.getLlmReasoning() != null) je.put("llm_reasoning", e.getLlmReasoning());
            entries.add(je);
        }
        json.put(FIELD_ENTRIES, entries);
        return json.toJSONString();
    }

    /** V5 init：根据模板 entries 拼出实际 entries（含 vat_settle 双分支 + 静态父级展开 + 4 级回退 + matched_via 字段） */
    String buildInitRulesJsonV5(DefaultCarryoverTemplate def,
                                 Map<String, MatchResult> bizToResult,
                                 Map<String, List<Subject>> leavesByParentCode,
                                 String taxpayerType) {
        JSONObject json = new JSONObject(true);
        json.put(FIELD_TEMPLATE_KIND, def.templateKind);
        json.put(FIELD_VOUCHER_SUMMARY, def.voucherSummary);
        json.put("taxpayer_type", taxpayerType);

        // vat_settle 选 entries 分支
        List<DefaultEntry> sourceEntries;
        if ("vat_settle".equals(def.key)) {
            sourceEntries = TP_SMALL_SCALE.equals(taxpayerType) ? def.entriesSmallScale : def.entries;
        } else {
            sourceEntries = def.entries;
        }

        JSONArray entries = new JSONArray();
        for (DefaultEntry de : sourceEntries) {
            if (Boolean.TRUE.equals(de.expandLeafWithBalance)
                    && PARENT_CODES_TO_EXPAND.contains(de.standardCode)) {
                // 静态父级展开（模板声明 expand_leaf_with_balance=true）：matched_via=parent_expand
                List<Subject> leaves = leavesByParentCode.getOrDefault(de.standardCode, List.of());
                for (Subject leaf : leaves) {
                    entries.add(buildExpandedLeafEntry(de, leaf, de.standardCode, VIA_PARENT_EXPAND));
                }
            } else {
                MatchResult result = bizToResult.get(de.bizMeaning);
                if (result != null && result.isExpanded()) {
                    // 动态展开：Level 2 parent_expand 或 Level 3b parent_expand_l1（matched_via 取 result.matchedVia）
                    for (Subject leaf : result.expandedLeaves) {
                        entries.add(buildExpandedLeafEntry(de, leaf, de.standardCode, result.matchedVia));
                    }
                } else {
                    entries.add(buildSingleEntry(de, result));
                }
            }
        }
        json.put(FIELD_ENTRIES, entries);
        return json.toJSONString();
    }

    /** 展开 leaf 为 1 条 entry（静态 PARENT_CODES_TO_EXPAND 用 parent_expand；动态 L2/L3b 用 result.matchedVia） */
    private JSONObject buildExpandedLeafEntry(DefaultEntry de, Subject leaf, String fromParent, String matchedVia) {
        JSONObject je = new JSONObject(true);
        je.put("direction", de.direction);
        je.put("biz_meaning", de.bizMeaning);
        je.put("standard_code", de.standardCode);
        je.put("subject_id", String.valueOf(leaf.getId()));
        je.put("subject_code", leaf.getCode());
        je.put("subject_name", leaf.getFullName());
        je.put("entry_summary", de.entrySummary);
        String amountDsl = de.amountDsl != null
                ? de.amountDsl.replace("<此 leaf>", leaf.getFullName() != null ? leaf.getFullName() : leaf.getCode())
                : null;
        je.put("amount_dsl", amountDsl);
        if (de.formulaDescription != null) je.put("formula_description", de.formulaDescription);
        je.put("source", ENTRY_SRC_PRESET_MATCHED);
        je.put("matched_via", matchedVia);
        je.put("expanded_from_parent", fromParent);
        return je;
    }

    /** 单 subject 入 1 条 entry（Level 1/3/4 + 平衡行 + missing） */
    private JSONObject buildSingleEntry(DefaultEntry de, MatchResult result) {
        JSONObject je = new JSONObject(true);
        je.put("direction", de.direction);
        je.put("biz_meaning", de.bizMeaning);
        je.put("standard_code", de.standardCode);
        if (result != null && result.subject != null) {
            je.put("subject_id", String.valueOf(result.subject.getId()));
            je.put("subject_code", result.subject.getCode());
            je.put("subject_name", result.subject.getFullName());
            je.put("source", ENTRY_SRC_PRESET_MATCHED);
            je.put("matched_via", result.matchedVia);
        } else {
            je.put("subject_id", null);
            je.put("subject_code", null);
            je.put("subject_name", null);
            je.put("source", ENTRY_SRC_MISSING);
            je.put("matched_via", VIA_MISSING);
        }
        je.put("entry_summary", de.entrySummary);
        je.put("amount_dsl", de.amountDsl);
        if (de.formulaDescription != null) je.put("formula_description", de.formulaDescription);
        return je;
    }

    Map<String, Object> mergeOne(DefaultCarryoverTemplate def, AccountSetProfile override) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", def.key);
        m.put("nameCn", def.nameCn);
        m.put("defaultTemplateKind", def.templateKind);
        m.put("voucherSummary", def.voucherSummary);
        m.put("defaultBusinessDescription", def.businessDescription);

        boolean overridden = override != null;
        m.put("isOverridden", overridden);

        if (overridden) {
            JSONObject rules = parseRulesSafe(override.getRules());
            String kind = rules == null ? null : rules.getString(FIELD_TEMPLATE_KIND);
            String summary = rules == null ? null : rules.getString(FIELD_VOUCHER_SUMMARY);
            JSONArray entriesArr = rules == null ? null : rules.getJSONArray(FIELD_ENTRIES);
            String v3FormulaDsl = rules == null ? null : rules.getString(FIELD_FORMULA_DSL_V3);

            m.put("templateKind", kind != null ? kind : def.templateKind);
            m.put("voucherSummaryOverride", summary);
            m.put("entries", entriesArr != null ? entriesArr : defaultEntriesAsJson(def));
            m.put("source", override.getSource());
            m.put("updatedBy", override.getUpdatedBy());
            m.put("updatedAt", override.getUpdatedAt());
            m.put("profileId", String.valueOf(override.getId()));
            if (v3FormulaDsl != null) m.put("legacyFormulaDsl", v3FormulaDsl);
        } else {
            m.put("templateKind", def.templateKind);
            m.put("voucherSummaryOverride", null);
            m.put("entries", defaultEntriesAsJson(def));
            m.put("source", null);
            m.put("updatedBy", null);
            m.put("updatedAt", null);
            m.put("profileId", null);
        }
        return m;
    }

    JSONArray defaultEntriesAsJson(DefaultCarryoverTemplate def) {
        JSONArray arr = new JSONArray();
        for (DefaultEntry de : def.entries) {
            JSONObject je = new JSONObject(true);
            je.put("direction", de.direction);
            je.put("biz_meaning", de.bizMeaning);
            je.put("standard_code", de.standardCode);
            je.put("subject_id", null);
            je.put("subject_code", null);
            je.put("subject_name", null);
            je.put("entry_summary", de.entrySummary);
            je.put("amount_dsl", de.amountDsl);
            if (de.formulaDescription != null) je.put("formula_description", de.formulaDescription);
            if (Boolean.TRUE.equals(de.expandLeafWithBalance)) je.put("expand_leaf_with_balance", true);
            je.put("source", ENTRY_SRC_MISSING);
            arr.add(je);
        }
        return arr;
    }

    JSONObject parseRulesSafe(String rulesJson) {
        if (rulesJson == null || rulesJson.isBlank()) return null;
        try {
            return JSON.parseObject(rulesJson);
        } catch (Exception e) {
            log.warn("[FN-CARRYOVER-V5] rules JSON 解析失败：{}", rulesJson);
            return null;
        }
    }

    static Long parseSubjectIdSafe(String s) {
        if (s == null) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static JsonResult failureWithType(String msg, String errorType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorType", errorType);
        return JsonResult.failure(msg).setData(data);
    }

    boolean accountSetExists(Long accountSetsId) {
        return bqf.selectFrom(qAccountSets).select(qAccountSets.id)
                .where(qAccountSets.id.eq(accountSetsId))
                .fetchFirst() != null;
    }

    /** FN-FIX-CARRYOVER-V5-MATCH-FALLBACK-CHAIN：返回账套全部科目（含 leaf=0），用于 4 级回退匹配
     *  Level 2 检测 exact code 是否 leaf=0、Level 3 截短试父级 leaf 都依赖看到 leaf=0 的行 */
    List<Subject> loadAllSubjectsForTest(Long accountSetsId) {
        return bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId))
                .fetch();
    }

    /** 查 account_sets.vat_type → small_scale / general */
    String loadTaxpayerTypeForTest(Long accountSetsId) {
        VatType vt = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(accountSetsId))
                .select(qAccountSets.vatType)
                .fetchFirst();
        if (vt == VatType.COMMONLY) return TP_GENERAL;
        return TP_SMALL_SCALE;
    }

    Map<Long, Subject> loadLeafSubjectsByIds(Long accountSetsId, Set<Long> subjectIds) {
        if (subjectIds.isEmpty()) return Map.of();
        List<Subject> rows = bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.id.in(subjectIds))
                        .and(qSubject.leaf.isTrue()))
                .fetch();
        Map<Long, Subject> m = new HashMap<>();
        for (Subject s : rows) m.put(s.getId(), s);
        return m;
    }

    /** 该账套是否已有 carryover_rule 数据（任一张模板的 rules.entries 长度 > 0）— 给 batch-init 用 */
    boolean hasExistingCarryoverData(Long accountSetsId) {
        List<AccountSetProfile> all = profileRepository.findByAccountSetsIdOrderByPriorityDescIdAsc(accountSetsId);
        for (AccountSetProfile p : all) {
            if (!SECTION_TYPE_CARRYOVER.equals(p.getSectionType())) continue;
            if (!Boolean.TRUE.equals(p.getEnabled())) continue;
            JSONObject rules = parseRulesSafe(p.getRules());
            if (rules == null) continue;
            JSONArray entries = rules.getJSONArray(FIELD_ENTRIES);
            if (entries != null && !entries.isEmpty()) return true;
        }
        return false;
    }

    /** 单账套 init 入口供 batch 复用（同事务） */
    JsonResult initInternal(Long accountSetsId, boolean force) {
        InitReq req = new InitReq();
        req.setForce(force);
        return init(accountSetsId, req, force);
    }

    // ═══════════════════════════════════════════════════════════════
    // DTO / 静态类
    // ═══════════════════════════════════════════════════════════════

    @Data
    public static class TemplateReq {
        private String key;
        private String templateKind;
        private String voucherSummary;
        private List<EntryReq> entries;
        private String source;
        private Long updatedBy;
    }

    @Data
    public static class EntryReq {
        private String direction;
        private String bizMeaning;
        /** V5 新增：标准 code（来自 SUBJECT_KEYWORDS_V5）*/
        private String standardCode;
        private String subjectId;
        private String entrySummary;
        private String amountDsl;
        /** V5 新增：给会计看的中文公式说明 */
        private String formulaDescription;
        private String source;
        private String llmReasoning;
    }

    @Data
    public static class InitReq {
        private Boolean overrideExisting;
        /** V5 新增：force=true 时覆盖 source=accountant_custom */
        private Boolean force;
    }

    public static class DefaultCarryoverTemplate {
        public final String key;
        public final String nameCn;
        public final String templateKind;
        public final String voucherSummary;
        public final String businessDescription;
        public final List<DefaultEntry> entries;
        /** vat_settle 小规模专用 entries（其他模板为空 list） */
        public final List<DefaultEntry> entriesSmallScale;

        public DefaultCarryoverTemplate(String key, String nameCn, String templateKind,
                                        String voucherSummary, String businessDescription,
                                        List<DefaultEntry> entries) {
            this(key, nameCn, templateKind, voucherSummary, businessDescription, entries, List.of());
        }

        public DefaultCarryoverTemplate(String key, String nameCn, String templateKind,
                                        String voucherSummary, String businessDescription,
                                        List<DefaultEntry> entries,
                                        List<DefaultEntry> entriesSmallScale) {
            this.key = key;
            this.nameCn = nameCn;
            this.templateKind = templateKind;
            this.voucherSummary = voucherSummary;
            this.businessDescription = businessDescription;
            this.entries = List.copyOf(entries);
            this.entriesSmallScale = List.copyOf(entriesSmallScale);
        }
    }

    public static class DefaultEntry {
        public final String direction;
        public final String bizMeaning;
        /** V5 新增：精确 standard_code（对应 SUBJECT_KEYWORDS_V5）*/
        public final String standardCode;
        public final String entrySummary;
        public final String amountDsl;
        /** V5 新增：中文公式说明（含税目税率明细）*/
        public final String formulaDescription;
        /** V5 新增：true 时 init 对该 standard_code（父级）做 leaf 展开 */
        public final Boolean expandLeafWithBalance;

        public DefaultEntry(String direction, String bizMeaning, String standardCode,
                            String entrySummary, String amountDsl) {
            this(direction, bizMeaning, standardCode, entrySummary, amountDsl, null, false);
        }

        public DefaultEntry(String direction, String bizMeaning, String standardCode,
                            String entrySummary, String amountDsl,
                            String formulaDescription, boolean expandLeafWithBalance) {
            this.direction = direction;
            this.bizMeaning = bizMeaning;
            this.standardCode = standardCode;
            this.entrySummary = entrySummary;
            this.amountDsl = amountDsl;
            this.formulaDescription = formulaDescription;
            this.expandLeafWithBalance = expandLeafWithBalance;
        }
    }

    public static class SubjectKeywordRule {
        public final String bizMeaning;
        public final String codePrefix;

        public SubjectKeywordRule(String bizMeaning, String codePrefix) {
            this.bizMeaning = bizMeaning;
            this.codePrefix = codePrefix;
        }
    }

    static class MatchedSubject {
        final Subject subject;
        final String source;
        MatchedSubject(Subject subject, String source) {
            this.subject = subject;
            this.source = source;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-FIX-CARRYOVER-V5-MATCH-FALLBACK-CHAIN-SIMPLIFIED：3 步必定终止
    //
    // 业务铁律：一级科目（4 位 code）肯定有一个 leaf=1。
    // 简化链：
    //   ① exact            — 精确 X leaf=1
    //   ② parent_expand    — X leaf=0 父级 → 展开 X 下 leaf 子科目
    //   ③ X 不存在 → 截短到一级 X4 = X.substring(0,4)：
    //       3a parent_leaf      — X4 leaf=1
    //       3b parent_expand_l1 — X4 leaf=0 → 展开 X4 下 leaf 子科目
    //   ④ missing — X4 也不存在（极端账套残缺）
    //
    // 简化掉（vs 旧 4 级链）：
    //   - 「子→父逐级 6→5 位中间」删除（一级=4 位标准，跳到 X4 即可）
    //   - 「子级最短 LIKE 'X%'」删除（被 X4 父级展开覆盖）
    // ═══════════════════════════════════════════════════════════════

    /** matched_via 取值：3 步链 + missing */
    static final String VIA_EXACT = "exact";
    static final String VIA_PARENT_EXPAND = "parent_expand";
    static final String VIA_PARENT_LEAF = "parent_leaf";
    static final String VIA_PARENT_EXPAND_L1 = "parent_expand_l1";
    static final String VIA_MISSING = "missing";

    /** 一级科目 code 长度（业务铁律：一级 = 4 位） */
    static final int PRIMARY_CODE_LENGTH = 4;

    /** 3 步匹配回退结果。
     *  exact / parent_leaf 返回单 subject；
     *  parent_expand / parent_expand_l1 返回 expandedLeaves（多 leaf）；
     *  missing 两者皆空。
     */
    static class MatchResult {
        final Subject subject;
        final List<Subject> expandedLeaves;
        final String matchedVia;

        private MatchResult(Subject subject, List<Subject> expandedLeaves, String matchedVia) {
            this.subject = subject;
            this.expandedLeaves = expandedLeaves == null ? List.of() : List.copyOf(expandedLeaves);
            this.matchedVia = matchedVia;
        }
        static MatchResult exact(Subject s) { return new MatchResult(s, null, VIA_EXACT); }
        static MatchResult parentExpand(List<Subject> leaves) { return new MatchResult(null, leaves, VIA_PARENT_EXPAND); }
        static MatchResult parentLeaf(Subject s) { return new MatchResult(s, null, VIA_PARENT_LEAF); }
        static MatchResult parentExpandL1(List<Subject> leaves) { return new MatchResult(null, leaves, VIA_PARENT_EXPAND_L1); }
        static MatchResult missing() { return new MatchResult(null, null, VIA_MISSING); }

        boolean isMatched() { return subject != null || !expandedLeaves.isEmpty(); }
        boolean isExpanded() { return !expandedLeaves.isEmpty(); }
    }

    /** 3 步匹配回退链（业务铁律：一级=4 位，3 步必定终止 — 详见 32_结转模板V5_预设科目映射.md）：
     *  <ol>
     *    <li>{@code exact}            — 精确 X + leaf=1</li>
     *    <li>{@code parent_expand}    — X 行存在但 leaf=0 → 展开 X 下 leaf 子科目（覆盖 5403 拆 14 子的场景）</li>
     *    <li>X 不存在 → 截短到 X4 = X.substring(0,4)（一级 code）：
     *        <ul>
     *          <li>{@code parent_leaf}      — X4 leaf=1 直接用（覆盖 3104001 → 3104 leaf 的场景）</li>
     *          <li>{@code parent_expand_l1} — X4 leaf=0 → 展开 X4 下 leaf 子科目</li>
     *        </ul>
     *    </li>
     *    <li>{@code missing} — X4 也不存在（极端账套残缺）</li>
     *  </ol>
     */
    MatchResult matchWithFallback(String standardCode, List<Subject> allSubjects, Map<String, Subject> byCode) {
        Subject exact = byCode.get(standardCode);

        // Level 1: 精确 X + leaf=1
        if (exact != null && Boolean.TRUE.equals(exact.getLeaf())) {
            return MatchResult.exact(exact);
        }

        // Level 2: X 行存在但 leaf=0 → 展开 X 下 leaf 子科目
        if (exact != null && Boolean.FALSE.equals(exact.getLeaf())) {
            List<Subject> children = collectLeafChildren(standardCode, allSubjects);
            if (!children.isEmpty()) {
                return MatchResult.parentExpand(children);
            }
            // 无 leaf 子代 — 继续走 Level 3
        }

        // Level 3: X 不存在 → 截短到一级 X4（仅 X 长度 > 4 时有意义）
        if (standardCode.length() > PRIMARY_CODE_LENGTH) {
            String x4 = standardCode.substring(0, PRIMARY_CODE_LENGTH);
            Subject x4Subj = byCode.get(x4);
            if (x4Subj != null) {
                if (Boolean.TRUE.equals(x4Subj.getLeaf())) {
                    // 3a: X4 leaf=1 直接用
                    return MatchResult.parentLeaf(x4Subj);
                }
                if (Boolean.FALSE.equals(x4Subj.getLeaf())) {
                    // 3b: X4 leaf=0 展开 X4 下 leaf 子科目
                    List<Subject> x4Children = collectLeafChildren(x4, allSubjects);
                    if (!x4Children.isEmpty()) {
                        return MatchResult.parentExpandL1(x4Children);
                    }
                }
            }
        }

        // Level 4: missing
        return MatchResult.missing();
    }

    /** 收集 parentCode 下所有 leaf=1 子科目（startsWith + !equals + leaf=true），按 code 升序 */
    private List<Subject> collectLeafChildren(String parentCode, List<Subject> allSubjects) {
        return allSubjects.stream()
                .filter(s -> s.getCode() != null
                        && s.getCode().startsWith(parentCode)
                        && !s.getCode().equals(parentCode)
                        && Boolean.TRUE.equals(s.getLeaf()))
                .sorted(Comparator.comparing(Subject::getCode))
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════
    // V4 xiaowei HTTP 调用结构（保留 — 测试 / future 工单可启用，init 主路径已不调）
    // ═══════════════════════════════════════════════════════════════

    XiaoweiMatchResult callXiaoweiMatchSubjectsForTest(Long accountSetsId, List<Subject> leafSubjects) {
        // V5 默认不调用：init 主路径走本地预设。保留方法是因为 V4 测试仍 mock 此 hook。
        return XiaoweiMatchResult.failure("v5_local_preset_skipped");
    }

    static class XiaoweiMatchResult {
        final boolean success;
        final Map<String, XiaoweiMatchedEntry> bizToMatched;
        final String failureReason;

        private XiaoweiMatchResult(boolean success, Map<String, XiaoweiMatchedEntry> bizToMatched, String failureReason) {
            this.success = success;
            this.bizToMatched = bizToMatched;
            this.failureReason = failureReason;
        }
        static XiaoweiMatchResult success(Map<String, XiaoweiMatchedEntry> bizToMatched) {
            return new XiaoweiMatchResult(true, bizToMatched, null);
        }
        static XiaoweiMatchResult failure(String reason) {
            return new XiaoweiMatchResult(false, Map.of(), reason);
        }
    }

    static class XiaoweiMatchedEntry {
        final Long subjectId;
        final String source;
        final String llmReasoning;
        XiaoweiMatchedEntry(Long subjectId, String source, String llmReasoning) {
            this.subjectId = subjectId;
            this.source = source;
            this.llmReasoning = llmReasoning;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 6 张系统默认模板（V5，对齐 项目工单\32_结转模板V5_预设科目映射.md）
    // ═══════════════════════════════════════════════════════════════

    private static List<DefaultCarryoverTemplate> buildDefaultTemplates() {
        List<DefaultCarryoverTemplate> list = new ArrayList<>();

        // ① pl_carry — 结转损益（balance_clear），12 项 entries 含 5 个父级展开
        list.add(new DefaultCarryoverTemplate("pl_carry", "结转损益", KIND_BALANCE_CLEAR,
                "结转本月损益至本年利润",
                "月末账结法：把损益类（5xxx/6xxx）期末余额清零，差额转入本年利润",
                List.of(
                        new DefaultEntry(DIR_DEBIT, "主营业务收入", "5001",
                                "结转收入", "主营业务收入.本期贷方余额",
                                "本期主营业务收入贷方期末余额", false),
                        new DefaultEntry(DIR_DEBIT, "其他业务收入", "5051",
                                "结转其他业务收入", "其他业务收入.本期贷方余额",
                                "本期其他业务收入贷方期末余额", false),
                        new DefaultEntry(DIR_DEBIT, "营业外收入", "5301",
                                "结转营业外收入(按 leaf 展开)", "<此 leaf>.本期贷方余额",
                                "营业外收入父级下每个 leaf 子科目本期贷方余额", true),
                        new DefaultEntry(DIR_CREDIT, "主营业务成本", "5401",
                                "结转销售成本", "主营业务成本.本期借方余额",
                                "本期主营业务成本借方期末余额", false),
                        new DefaultEntry(DIR_CREDIT, "其他业务成本", "5402",
                                "结转其他业务成本", "其他业务成本.本期借方余额",
                                "本期其他业务成本借方期末余额", false),
                        new DefaultEntry(DIR_CREDIT, "税金及附加", "5403",
                                "结转税金及附加", "税金及附加.本期借方余额",
                                "本期税金及附加借方期末余额", false),
                        new DefaultEntry(DIR_CREDIT, "销售费用", "5601",
                                "结转销售费用(按 leaf 展开)", "<此 leaf>.本期借方余额",
                                "销售费用父级下每个 leaf 子科目本期借方余额", true),
                        new DefaultEntry(DIR_CREDIT, "管理费用", "5602",
                                "结转管理费用(按 leaf 展开)", "<此 leaf>.本期借方余额",
                                "管理费用父级下每个 leaf 子科目本期借方余额", true),
                        new DefaultEntry(DIR_CREDIT, "财务费用", "5603",
                                "结转财务费用(按 leaf 展开)", "<此 leaf>.本期借方余额",
                                "财务费用父级下每个 leaf 子科目本期借方余额", true),
                        new DefaultEntry(DIR_CREDIT, "营业外支出", "5711",
                                "结转营业外支出(按 leaf 展开)", "<此 leaf>.本期借方余额",
                                "营业外支出父级下每个 leaf 子科目本期借方余额", true),
                        new DefaultEntry(DIR_CREDIT, "所得税费用", "5801",
                                "结转所得税费用", "所得税费用.本期借方余额",
                                "本期所得税费用借方期末余额", false),
                        new DefaultEntry(DIR_BALANCE, "本年利润", "3103",
                                "平衡至本年利润", "借贷差额",
                                "上面所有 entries 借贷差额自动平衡到本年利润", false)
                )));

        // ② cost_carry — 结转销售成本（amount_only）
        String invDsl = "库存商品.本期贷方累计";
        list.add(new DefaultCarryoverTemplate("cost_carry", "结转销售成本", KIND_AMOUNT_ONLY,
                "结转本月销售成本",
                "本月销售产品对应的库存商品成本同步转入主营业务成本",
                List.of(
                        new DefaultEntry(DIR_DEBIT, "主营业务成本", "5401",
                                "结转销售成本", invDsl,
                                "本月销售出库的库存商品贷方累计金额", false),
                        new DefaultEntry(DIR_CREDIT, "库存商品", "1405",
                                "冲减库存", invDsl,
                                "本月销售出库的库存商品贷方累计金额", false)
                )));

        // ③ surtax — 计提税金（multi_output，6 类税金 + 1 借方汇总）
        list.add(new DefaultCarryoverTemplate("surtax", "计提税金", KIND_MULTI_OUTPUT,
                "计提本期税金（含附加税 + 行为税）",
                "本期应计提附加税 + 印花税 + 房产税 + 城镇土地使用税；amount=0 的分录自动不入凭证",
                List.of(
                        new DefaultEntry(DIR_DEBIT, "税金及附加", "5403",
                                "计提本期税金",
                                "entry[城建税].amount + entry[教附].amount + entry[地教附].amount + entry[印花税].amount + entry[房产税].amount + entry[城镇土地使用税].amount",
                                "本期应计提全部税金合计（自动汇总 6 类贷方分录）", false),
                        new DefaultEntry(DIR_CREDIT, "应交城建税", "2221008",
                                "计提城建税",
                                "context.vat_payable * config.城建税率 * config.reduction_half_factor",
                                "本期应交增值税 × 城建税率（市区 7% / 县城 5% / 其他 1%）× 减半因子", false),
                        new DefaultEntry(DIR_CREDIT, "教育费附加", "2221013",
                                "计提教育费附加",
                                "context.vat_payable * 0.03 * config.reduction_half_factor",
                                "本期应交增值税 × 3% × 减半因子", false),
                        new DefaultEntry(DIR_CREDIT, "地方教育费附加", "2221014",
                                "计提地方教育费附加",
                                "context.vat_payable * 0.02 * config.reduction_half_factor",
                                "本期应交增值税 × 2% × 减半因子", false),
                        new DefaultEntry(DIR_CREDIT, "印花税", "2221017",
                                "计提印花税",
                                "if(config.印花税金额 != null, config.印花税金额, 主营业务收入.本期贷方累计 * 0.0003) * config.reduction_half_factor",
                                "印花税 = 各税目应税金额 × 税率合计。默认按主营业务收入 × 万分之三估算（仅购销合同部分）；会计有具体合同台账时通过 config.印花税金额 透传精确金额。税目税率：购销/承揽/建设/运输/技术 — 万分之三；借款 — 万分之零点五；租赁/保管/仓储/财产保险 — 千分之一；产权转移书据 — 万分之五；营业账簿 — 万分之二点五；证券交易 — 千分之一。", false),
                        new DefaultEntry(DIR_CREDIT, "应交房产税", "2221009",
                                "计提房产税",
                                "config.房产税金额 * config.reduction_half_factor",
                                "房产税 = config.房产税金额（按季度透传当期应缴金额，无透传则该分录金额=0 不入凭证）× 减半因子。税目税率：从价计征 — 房产原值 × (1 - 扣除率) × 1.2%；从租计征 — 租金 × 12%（个人住房 4%）。", false),
                        new DefaultEntry(DIR_CREDIT, "应交城镇土地使用税", "2221010",
                                "计提城镇土地使用税",
                                "config.城镇土地使用税金额 * config.reduction_half_factor",
                                "城镇土地使用税 = config.城镇土地使用税金额 × 减半因子。公式：占用土地面积（平方米）× 当地等级单价（元/平米/年）/ 4（按季度计提）。各等级单价：大城市 1.5-30 元 / 中等城市 1.2-24 元 / 小城市 0.9-18 元 / 县城建制镇 0.6-12 元。", false)
                )));

        // ④ vat_settle — 双分支（一般纳税人 / 小规模）
        List<DefaultEntry> vatGeneral = List.of(
                new DefaultEntry(DIR_DEBIT, "转出未交增值税", "2221001003",
                        "转出本月增值税",
                        "max(销项税额.本期贷方累计 - 进项税额.本期借方累计 + 进项税额转出.本期贷方累计, 0)",
                        "一般纳税人本期应纳增值税 = max(销项-进项+转出, 0)；≤0 时不结转保留为留抵", false),
                new DefaultEntry(DIR_CREDIT, "未交增值税", "2221002",
                        "形成未交增值税",
                        "max(销项税额.本期贷方累计 - 进项税额.本期借方累计 + 进项税额转出.本期贷方累计, 0)",
                        "一般纳税人本期应纳增值税 = max(销项-进项+转出, 0)；≤0 时不结转保留为留抵", false)
        );
        List<DefaultEntry> vatSmallScale = List.of(
                new DefaultEntry(DIR_DEBIT, "应交增值税(小规模专用)", "2221026",
                        "转出本月增值税",
                        "应交增值税(小规模专用).本期贷方累计",
                        "小规模纳税人按 2221026 汇总本期应交增值税额一次性结转", false),
                new DefaultEntry(DIR_CREDIT, "未交增值税", "2221002",
                        "形成未交增值税",
                        "应交增值税(小规模专用).本期贷方累计",
                        "小规模纳税人按 2221026 汇总本期应交增值税额一次性结转", false)
        );
        list.add(new DefaultCarryoverTemplate("vat_settle", "结转未交增值税", KIND_VAT_SETTLE,
                "月末结转应交未交增值税",
                "一般纳税人按销项-进项+转出净额结转；小规模按 2221026 汇总结转",
                vatGeneral, vatSmallScale));

        // ⑤ income_tax — 计提所得税（amount_only）
        list.add(new DefaultCarryoverTemplate("income_tax", "计提所得税", KIND_AMOUNT_ONLY,
                "计提本季度企业所得税",
                "季末按累计应纳税所得额 × 税率",
                List.of(
                        new DefaultEntry(DIR_DEBIT, "所得税费用", "5801",
                                "计提所得税",
                                "context.taxable_income * config.income_tax_rate",
                                "本期应纳税所得额 × 所得税率（小微 0.05 / 标准 0.25）", false),
                        new DefaultEntry(DIR_CREDIT, "应交所得税", "2221006",
                                "应交所得税",
                                "context.taxable_income * config.income_tax_rate",
                                "本期应纳税所得额 × 所得税率（小微 0.05 / 标准 0.25）", false)
                )));

        // ⑥ year_profit_carry — 结转本年利润（amount_only，仅 12 月；盈利分支默认，亏损让会计 BT 改）
        list.add(new DefaultCarryoverTemplate("year_profit_carry", "结转本年利润", KIND_AMOUNT_ONLY,
                "年末结转本年利润至未分配利润",
                "12 月年末根据本年利润余额方向选择盈利/亏损分支（盈利：借 3103 贷 3104001；亏损：借 3104001 贷 3103）",
                List.of(
                        new DefaultEntry(DIR_DEBIT, "本年利润", "3103",
                                "结转本年盈利",
                                "abs(本年利润.年末贷方余额)",
                                "盈利时（本年利润贷方有余额）借本年利润对应金额；亏损时会计 BT 改为借未分配利润", false),
                        new DefaultEntry(DIR_CREDIT, "未分配利润", "3104001",
                                "增加未分配利润",
                                "abs(本年利润.年末贷方余额)",
                                "盈利时贷未分配利润对应金额；亏损时会计 BT 改为贷本年利润", false)
                )));

        return list;
    }
}
