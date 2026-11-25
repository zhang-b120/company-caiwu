package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSetProfile;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.repo.AccountSetProfileRepository;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 开放 API — 账套画像（FN-NEW-ACCOUNT-SET-PROFILE）。
 * <p>路径前缀 {@code /openapi/v1/account-sets/{id}/profile}。
 *
 * <ul>
 *   <li>GET → 拉账套全部 section（按 priority 倒序）</li>
 *   <li>PUT → 批量 upsert（按 section_type + section_key 合并）</li>
 *   <li>DELETE /{section_type}/{section_key} → 软删（enabled=false）</li>
 * </ul>
 *
 * <p>FN 端**不解析** rules/prompt 字段，仅做 JSON / TEXT 透传。
 * rules 的 rule_template 语义由 xiaowei 端定义并执行。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/account-sets/{accountSetsId}/profile")
@SaIgnore
public class OpenApiAccountSetProfileController {

    /**
     * section_type 白名单。
     * <p>FN-TAX-VERIFY-PROFILE-WIRE：新增 {@code tax_verify}（税务核验/资格），由 FN 收到 BT 核验结果后
     * 系统写入（source=system_suggested），与会计录入的 business_rule 等同列展示。
     */
    static final Set<String> ALLOWED_SECTION_TYPES = Set.of(
            // ACCOUNT-PROFILE-V2 六块（目标）
            "invoice_rule", "bank_rule", "enterprise_tax", "subject_aux", "carryover_accrual",
            // 系统/V5：tax_verify（系统写入）、carryover_rule（V5 结转模板系统在用，不动）
            "tax_verify", "carryover_rule",
            // 过渡期保留旧类型可写（迁移期间 BT/XW 渐进切换；存量数据由 V20260609_001 迁到新类型）。
            // 待 BT/XW 完成切换后单独工单移除：business_rule→invoice_rule、industry/tax_policy→enterprise_tax、note/exception 废弃。
            "business_rule", "note", "industry", "exception", "tax_policy"
    );

    /** ACCOUNT-PROFILE-V2：status 三态白名单。 */
    static final Set<String> ALLOWED_STATUS = Set.of("active", "pending_review", "disabled");

    /** carryover_rule section_type 常量 */
    static final String SECTION_TYPE_CARRYOVER = "carryover_rule";

    /**
     * FN-FIX-CARRYOVER-V5-E2E-TEST-AND-CLEANUP：V5 carryover_rule 的 section_key 6 项白名单。
     * <p>通用 PUT /profile 在 section_type=carryover_rule 时必须强校验 section_key 在此白名单内，
     * 防止 BT 或其他客户端通过通用 profile 接口绕过 carryover-templates controller 写入 V4 旧 key
     * （V4 13 项 key 已废弃）。
     * <p>必须与 {@code OpenApiCarryoverTemplatesController.DEFAULT_BY_KEY.keySet()} 保持同步。
     */
    static final Set<String> ALLOWED_V5_CARRYOVER_KEYS = Set.of(
            "pl_carry", "cost_carry", "surtax", "vat_settle", "income_tax", "year_profit_carry"
    );

    /**
     * FN-NEW-CONFIG-INIT-SOURCE-FIELD：4 个 source 白名单 + 默认值。
     * <p>优先级（从高到低）：accountant_custom > accountant_reviewed > system_suggested > auto_matched
     */
    static final String SOURCE_AUTO_MATCHED = "auto_matched";
    static final String SOURCE_ACCOUNTANT_CUSTOM = "accountant_custom";
    static final String SOURCE_ACCOUNTANT_REVIEWED = "accountant_reviewed";
    static final String SOURCE_SYSTEM_SUGGESTED = "system_suggested";
    static final String SOURCE_WARBOOK_DEFAULT = "warbook_default";  // ACCOUNT-PROFILE-V2：行业作战手册默认兜底
    static final String SOURCE_DEFAULT = SOURCE_ACCOUNTANT_CUSTOM;
    static final Set<String> ALLOWED_SOURCES = Set.of(
            SOURCE_AUTO_MATCHED, SOURCE_ACCOUNTANT_CUSTOM, SOURCE_ACCOUNTANT_REVIEWED,
            SOURCE_SYSTEM_SUGGESTED, SOURCE_WARBOOK_DEFAULT
    );

    /**
     * FN-NEW-CONFIG-INIT-SOURCE-FIELD：init-status 接口的"业务约定总数"映射。
     * <p>当前只 {@code carryover_rule} 有约定（13 条结转规则），其他 section_type 没有标准总数，
     * 返 0 表示"无约定上限"，调用方按 completed 计数自行判断。
     */
    static final Map<String, Integer> SECTION_TYPE_TOTAL_EXPECTED = Map.of(
            "carryover_rule", 6
    );

    private final AccountSetProfileRepository profileRepository;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QAccountSets qAccountSets = QAccountSets.accountSets;

    // ═══════════════════════════════════════════════════════════════
    // GET — 拉账套全部 section（按 priority 倒序，含已软删的 enabled=false）
    // ═══════════════════════════════════════════════════════════════

    @GetMapping
    public JsonResult list(@PathVariable Long accountSetsId,
                           @RequestParam(required = false, defaultValue = "false") Boolean enabledOnly) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (!accountSetExists(accountSetsId)) {
            return JsonResult.failure("账套不存在：" + accountSetsId);
        }

        List<AccountSetProfile> rows = profileRepository
                .findByAccountSetsIdOrderByPriorityDescIdAsc(accountSetsId);
        if (Boolean.TRUE.equals(enabledOnly)) {
            rows = rows.stream().filter(p -> Boolean.TRUE.equals(p.getEnabled())).toList();
        }

        List<Map<String, Object>> data = new ArrayList<>(rows.size());
        for (AccountSetProfile p : rows) data.add(toMap(p));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("accountSetsId", accountSetsId);
        resp.put("sections", data);
        return JsonResult.successful(resp);
    }

    // ═══════════════════════════════════════════════════════════════
    // PUT — 批量 upsert（按 section_type + section_key 合并）
    //   入参：[{section_type, section_key, title, rules, prompt, priority, enabled, ...}, ...]
    // ═══════════════════════════════════════════════════════════════

    @PutMapping
    @Transactional
    public JsonResult upsert(@PathVariable Long accountSetsId,
                             @RequestBody List<ProfileReq> reqs) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (reqs == null || reqs.isEmpty()) {
            return JsonResult.failure("画像列表不能为空");
        }
        if (!accountSetExists(accountSetsId)) {
            return JsonResult.failure("账套不存在：" + accountSetsId);
        }

        // 预校验：每条都要 section_type，且属于白名单（见 ALLOWED_SECTION_TYPES）；source 若传必须在白名单
        for (ProfileReq r : reqs) {
            if (r == null || r.getSectionType() == null || r.getSectionType().isBlank()) {
                return JsonResult.failure("section_type 不能为空");
            }
            if (!ALLOWED_SECTION_TYPES.contains(r.getSectionType())) {
                return JsonResult.failure("section_type 取值非法：" + r.getSectionType()
                        + "，允许：" + ALLOWED_SECTION_TYPES);
            }
            // FN-NEW-CONFIG-INIT-SOURCE-FIELD: source 字段白名单校验（null 时 controller 兜底 auto_matched）
            if (r.getSource() != null && !ALLOWED_SOURCES.contains(r.getSource())) {
                return JsonResult.failure("source 取值非法：" + r.getSource()
                        + "，允许：" + ALLOWED_SOURCES);
            }
            // ACCOUNT-PROFILE-V2: status 白名单 + confidence 区间校验
            if (r.getStatus() != null && !ALLOWED_STATUS.contains(r.getStatus())) {
                return JsonResult.failure("status 取值非法：" + r.getStatus() + "，允许：" + ALLOWED_STATUS);
            }
            if (r.getConfidence() != null
                    && (r.getConfidence().signum() < 0 || r.getConfidence().compareTo(java.math.BigDecimal.ONE) > 0)) {
                return JsonResult.failure("confidence 取值范围 0-1：" + r.getConfidence());
            }
            // FN-FIX-CARRYOVER-V5-E2E-TEST-AND-CLEANUP: carryover_rule 的 section_key 必须在 V5 6 项白名单内
            if (SECTION_TYPE_CARRYOVER.equals(r.getSectionType())) {
                String key = normalizeKey(r.getSectionKey());
                if (!ALLOWED_V5_CARRYOVER_KEYS.contains(key)) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("errorType", "INVALID_CARRYOVER_KEY");
                    err.put("sectionKey", key);
                    err.put("allowed", ALLOWED_V5_CARRYOVER_KEYS);
                    return JsonResult.failure("carryover_rule 的 section_key 不在 V5 6 项白名单："
                            + key + "（V4 旧 key 已废弃；允许：" + ALLOWED_V5_CARRYOVER_KEYS + "）")
                            .setData(err);
                }
            }
        }

        LocalDateTime now = LocalDateTime.now();
        int created = 0;
        int updated = 0;
        List<Map<String, Object>> resultRows = new ArrayList<>(reqs.size());

        // FN-FIX-DB-HEALTH-AND-HOTPATH §2：原逐条 findBy（60 条=60 查、@Transactional 全量回滚 = 假死热点）。
        // 改批量：一次拉本账套全部 section 建索引，循环内内存命中；末尾 saveAll 一次批量落库（DB 查询 ~60→≤2）。
        Map<String, AccountSetProfile> existingByKey = new HashMap<>();
        for (AccountSetProfile p : profileRepository.findByAccountSetsIdOrderByPriorityDescIdAsc(accountSetsId)) {
            existingByKey.put(p.getSectionType() + " " + p.getSectionKey(), p);
        }
        List<AccountSetProfile> toSave = new ArrayList<>(reqs.size());

        for (ProfileReq r : reqs) {
            String key = normalizeKey(r.getSectionKey());
            AccountSetProfile existing = existingByKey.get(r.getSectionType() + " " + key);

            AccountSetProfile entity;
            if (existing != null) {
                entity = existing;
                updated++;
            } else {
                entity = new AccountSetProfile();
                entity.setAccountSetsId(accountSetsId);
                entity.setSectionType(r.getSectionType());
                entity.setSectionKey(key);
                entity.setCreatedAt(now);
                created++;
                // 同批多条同 (type,key) 时后者命中前者，避免重复 insert 撞唯一键
                existingByKey.put(r.getSectionType() + " " + key, entity);
            }
            // title / rules / prompt / priority / enabled / effective_from/to / updated_by
            // 都允许为 null —— null 时不覆盖（保留原值）的语义反而复杂；
            // 这里按"传啥写啥"原则：null 直接覆盖（XW / BT 传入完整快照，缺字段表示清空）
            entity.setTitle(r.getTitle());
            // rules: Object → JSON 字符串（FN 不解析，存原文）
            entity.setRules(r.getRules() == null ? null : JSON.toJSONString(r.getRules()));
            entity.setPrompt(r.getPrompt());
            entity.setPriority(r.getPriority() == null ? 0 : r.getPriority());
            // ACCOUNT-PROFILE-V2：status 为主，enabled 过渡期同步（enabled = status='active'）。
            // 优先取 status；未传则由 enabled 派生（active/disabled）；都未传默认 active。
            String status;
            if (r.getStatus() != null) {
                status = r.getStatus();
            } else if (r.getEnabled() != null) {
                status = Boolean.TRUE.equals(r.getEnabled()) ? "active" : "disabled";
            } else {
                status = "active";
            }
            entity.setStatus(status);
            entity.setEnabled("active".equals(status));   // 过渡同步，避免与 status 冗余歧义
            entity.setConfidence(r.getConfidence());
            entity.setEffectiveFrom(r.getEffectiveFrom());
            entity.setEffectiveTo(r.getEffectiveTo());
            entity.setUpdatedBy(r.getUpdatedBy());
            // FN-NEW-CONFIG-INIT-SOURCE-FIELD: source 缺省 accountant_custom
            // （会计在 BT 端编辑写回视为 custom；XW LLM 初始化需要显式传 auto_matched；
            //  会计 review 完显式传 accountant_reviewed）
            entity.setSource(r.getSource() == null ? SOURCE_DEFAULT : r.getSource());
            entity.setUpdatedAt(now);

            toSave.add(entity);
        }

        // 批量落库（一次 saveAll；JPA 按主键有无分流 insert/update，减少往返）
        profileRepository.saveAll(toSave);
        for (AccountSetProfile saved : toSave) resultRows.add(toMap(saved));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("created", created);
        data.put("updated", updated);
        data.put("rows", resultRows);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-NEW-CONFIG-INIT-SOURCE-FIELD: 初始化状态查询
    // GET /openapi/v1/account-sets/{id}/profile/init-status?section_type=carryover_rule
    //
    // 返回 {totalExpected, completed, missing, bySource: {auto_matched, accountant_custom, accountant_reviewed}}
    //   totalExpected = 业务约定的目标条数（carryover_rule 固定 13；其他 section_type 返 0 表无约定上限）
    //   completed     = 该 section_type 已配置的启用条数（enabled=true）
    //   missing       = max(totalExpected - completed, 0)
    //   bySource      = 按 source 分组的计数（仅含启用项）
    //
    // xiaowei 用此接口判断要不要重跑 LLM 初始化：
    //   bySource 全 accountant_custom/reviewed → 不要碰（会计已介入）
    //   仅 auto_matched → 可以重跑替换
    //   missing>0 / completed=0 → 跑首次初始化
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/init-status")
    public JsonResult initStatus(@PathVariable Long accountSetsId,
                                 @RequestParam("section_type") String sectionType) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (sectionType == null || sectionType.isBlank()) {
            return JsonResult.failure("section_type 不能为空");
        }
        if (!ALLOWED_SECTION_TYPES.contains(sectionType)) {
            return JsonResult.failure("section_type 取值非法：" + sectionType
                    + "，允许：" + ALLOWED_SECTION_TYPES);
        }
        if (!accountSetExists(accountSetsId)) {
            return JsonResult.failure("账套不存在：" + accountSetsId);
        }

        // 拉本账套全部 profile，按 sectionType + enabled 过滤
        List<AccountSetProfile> all = profileRepository
                .findByAccountSetsIdOrderByPriorityDescIdAsc(accountSetsId);

        int completed = 0;
        Map<String, Integer> bySource = new LinkedHashMap<>();
        bySource.put(SOURCE_AUTO_MATCHED, 0);
        bySource.put(SOURCE_ACCOUNTANT_CUSTOM, 0);
        bySource.put(SOURCE_ACCOUNTANT_REVIEWED, 0);
        bySource.put(SOURCE_SYSTEM_SUGGESTED, 0);

        for (AccountSetProfile p : all) {
            if (!sectionType.equals(p.getSectionType())) continue;
            if (!Boolean.TRUE.equals(p.getEnabled())) continue;
            completed++;
            String src = p.getSource() == null ? SOURCE_DEFAULT : p.getSource();
            bySource.merge(src, 1, Integer::sum);
        }

        int totalExpected = SECTION_TYPE_TOTAL_EXPECTED.getOrDefault(sectionType, 0);
        int missing = Math.max(totalExpected - completed, 0);
        // 工单卡定义：needs_review = auto_matched + system_suggested（待会计审核 / 接受的项）
        int needsReview = bySource.get(SOURCE_AUTO_MATCHED) + bySource.get(SOURCE_SYSTEM_SUGGESTED);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountSetsId", accountSetsId);
        data.put("sectionType", sectionType);
        data.put("totalExpected", totalExpected);
        data.put("completed", completed);
        data.put("missing", missing);
        data.put("bySource", bySource);
        data.put("needsReview", needsReview);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // DELETE /{section_type}/{section_key} — 软删（enabled=false）
    // ═══════════════════════════════════════════════════════════════

    @DeleteMapping("/{sectionType}/{sectionKey}")
    @Transactional
    public JsonResult softDelete(@PathVariable Long accountSetsId,
                                 @PathVariable String sectionType,
                                 @PathVariable String sectionKey) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (sectionType == null || sectionType.isBlank()) {
            return JsonResult.failure("section_type 不能为空");
        }
        if (!ALLOWED_SECTION_TYPES.contains(sectionType)) {
            return JsonResult.failure("section_type 取值非法：" + sectionType);
        }
        if (!accountSetExists(accountSetsId)) {
            return JsonResult.failure("账套不存在：" + accountSetsId);
        }

        String key = normalizeKey(sectionKey);
        Optional<AccountSetProfile> existingOpt = profileRepository
                .findByAccountSetsIdAndSectionTypeAndSectionKey(accountSetsId, sectionType, key);

        int deleted = 0;
        if (existingOpt.isPresent()) {
            AccountSetProfile entity = existingOpt.get();
            // 已是软删状态视为成功（幂等）
            if (Boolean.TRUE.equals(entity.getEnabled())) {
                entity.setEnabled(Boolean.FALSE);
                entity.setStatus("disabled");   // ACCOUNT-PROFILE-V2：软删同步 status
                entity.setUpdatedAt(LocalDateTime.now());
                profileRepository.save(entity);
            }
            deleted = 1;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted", deleted);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════════

    /** section_key normalization：null / blank → 空串（与 UNIQUE 字段 NOT NULL 默认 '' 对齐） */
    static String normalizeKey(String key) {
        return key == null || key.isBlank() ? "" : key;
    }

    /** 把 entity 序列化成 JSON 响应。rules 字段把 JSON 字符串反序列化回 Object 让 client 直接拿对象。 */
    private Map<String, Object> toMap(AccountSetProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("accountSetsId", p.getAccountSetsId());
        m.put("sectionType", p.getSectionType());
        m.put("sectionKey", p.getSectionKey());
        m.put("title", p.getTitle());
        // rules 存的是 JSON 字符串，响应时尝试 parse 成 Object（让 client 直接拿对象）
        Object rulesObj = null;
        if (p.getRules() != null && !p.getRules().isBlank()) {
            try {
                rulesObj = JSON.parse(p.getRules());
            } catch (Exception e) {
                log.warn("[FN-NEW-ACCOUNT-SET-PROFILE] rules JSON 解析失败 id={} 原文={}", p.getId(), p.getRules());
                rulesObj = p.getRules();   // 回退给原字符串，client 自己处理
            }
        }
        m.put("rules", rulesObj);
        m.put("prompt", p.getPrompt());
        m.put("priority", p.getPriority());
        m.put("enabled", p.getEnabled());
        m.put("status", p.getStatus());           // ACCOUNT-PROFILE-V2
        m.put("confidence", p.getConfidence());   // ACCOUNT-PROFILE-V2
        m.put("effectiveFrom", p.getEffectiveFrom());
        m.put("effectiveTo", p.getEffectiveTo());
        m.put("updatedBy", p.getUpdatedBy());
        // FN-NEW-CONFIG-INIT-SOURCE-FIELD: source 字段（auto_matched/accountant_custom/accountant_reviewed）
        m.put("source", p.getSource());
        m.put("createdAt", p.getCreatedAt());
        m.put("updatedAt", p.getUpdatedAt());
        return m;
    }

    /** package-private test hook：单测覆写避开 BlazeJPAQuery 直查 */
    boolean accountSetExists(Long accountSetsId) {
        return bqf.selectFrom(qAccountSets).select(qAccountSets.id)
                .where(qAccountSets.id.eq(accountSetsId))
                .fetchFirst() != null;
    }

    @Data
    public static class ProfileReq {
        private String sectionType;
        private String sectionKey;
        private String title;
        /** JSON 对象，FN 不解析（Jackson 自动反序列化成 Map / List / 嵌套） */
        private Object rules;
        private String prompt;
        private Integer priority;
        private Boolean enabled;
        /** ACCOUNT-PROFILE-V2：状态 active/pending_review/disabled（主），不传则由 enabled 派生。 */
        private String status;
        /** ACCOUNT-PROFILE-V2：置信度 0-1（学习/AI 建议），会计录入留空。 */
        private java.math.BigDecimal confidence;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private Long updatedBy;
        /**
         * FN-NEW-CONFIG-INIT-SOURCE-FIELD：来源标记，可选。
         * 不传时按 {@code accountant_custom} 落盘（会计编辑视为 custom）。
         * 三值：{@code auto_matched / accountant_custom / accountant_reviewed}。
         */
        private String source;
    }
}
