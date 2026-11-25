package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.entity.QSubject;
import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.enums.SubjectCategory;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.service.SubjectService;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 开放 API — 科目表
 * 路径前缀 /openapi/v1/subjects
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/subjects")
@SaIgnore
public class OpenApiSubjectController {

    private final SubjectService subjectService;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QAccountSets qAccountSets = QAccountSets.accountSets;
    private static final QSubject qSubject = QSubject.subject;

    /**
     * FN-NEW-OPENAPI-ENSURE-SUBJECTS：业务含义代码 → 本系统标准科目（一级，4 位编码，与编码规则无关可固定）。
     * ⚠️ 这套 key 必须与 XW-NEW-SUBJECT-PREFLIGHT 对齐，敲定后写进 docs/openapi-bt-xw.yaml。
     * 多级科目（如增值税进项/销项/未交明细）编码随账套编码规则变化、无法固定，统一走 detail 路径
     * （传 parentCode + detailName）解决。
     */
    static final Map<String, StdSubject> STANDARD = buildStandardMap();

    private static Map<String, StdSubject> buildStandardMap() {
        Map<String, StdSubject> m = new LinkedHashMap<>();
        // 资产
        m.put("CASH_ON_HAND", new StdSubject("1001", "库存现金", "资产", "借"));
        m.put("BANK_DEPOSIT", new StdSubject("1002", "银行存款", "资产", "借"));
        m.put("AR", new StdSubject("1122", "应收账款", "资产", "借"));
        m.put("PREPAYMENT", new StdSubject("1123", "预付账款", "资产", "借"));
        m.put("OTHER_RECEIVABLE", new StdSubject("1221", "其他应收款", "资产", "借"));
        m.put("RAW_MATERIAL", new StdSubject("1403", "原材料", "资产", "借"));
        m.put("INVENTORY_GOODS", new StdSubject("1405", "库存商品", "资产", "借"));
        m.put("FIXED_ASSET", new StdSubject("1601", "固定资产", "资产", "借"));
        m.put("ACCUM_DEPRECIATION", new StdSubject("1602", "累计折旧", "资产", "贷"));
        // 负债
        m.put("AP", new StdSubject("2202", "应付账款", "负债", "贷"));
        m.put("ADVANCE_RECEIPT", new StdSubject("2203", "预收账款", "负债", "贷"));
        m.put("SALARY_PAYABLE", new StdSubject("2211", "应付职工薪酬", "负债", "贷"));
        m.put("TAX_PAYABLE", new StdSubject("2221", "应交税费", "负债", "贷"));
        m.put("OTHER_PAYABLE", new StdSubject("2241", "其他应付款", "负债", "贷"));
        // 权益
        m.put("PROFIT_CURRENT", new StdSubject("3103", "本年利润", "权益", "贷"));
        m.put("PROFIT_DISTRIBUTION", new StdSubject("3104", "利润分配", "权益", "贷"));
        // 损益
        m.put("REVENUE_MAIN", new StdSubject("5001", "主营业务收入", "损益", "贷"));
        m.put("COST_MAIN", new StdSubject("5401", "主营业务成本", "损益", "借"));
        m.put("SELLING_EXPENSE", new StdSubject("5601", "销售费用", "损益", "借"));
        m.put("ADMIN_EXPENSE", new StdSubject("5602", "管理费用", "损益", "借"));
        m.put("FINANCE_EXPENSE", new StdSubject("5603", "财务费用", "损益", "借"));
        return Collections.unmodifiableMap(m);
    }

    /**
     * 查询账套科目表
     * GET /openapi/v1/subjects?accountSetsId=88&status=true&leafOnly=false
     */
    @GetMapping
    public JsonResult list(@RequestParam Long accountSetsId,
                           @RequestParam(required = false, defaultValue = "true") Boolean status,
                           @RequestParam(required = false, defaultValue = "false") Boolean leafOnly) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }

        List<Subject> subjects = Boolean.TRUE.equals(leafOnly)
                ? subjectService.selectLeafData(accountSetsId)
                : subjectService.selectData(accountSetsId);

        if (subjects == null) {
            subjects = Collections.emptyList();
        }

        // status=true 时只返回启用的；status=false 返回全部
        boolean enabledOnly = !Boolean.FALSE.equals(status);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Subject s : subjects) {
            if (enabledOnly && !Boolean.TRUE.equals(s.getStatus())) {
                continue;
            }
            result.add(toDto(s));
        }
        return JsonResult.successful(result);
    }

    /** Subject.type 数据库存的是中文（display），转回英文枚举名对外暴露 */
    private static String typeToEnumName(String chineseType) {
        if (chineseType == null || chineseType.isEmpty()) return null;
        for (SubjectCategory cat : SubjectCategory.values()) {
            if (cat.display.equals(chineseType)) {
                return cat.name();
            }
        }
        // 兜底：如果数据库已经是英文（向后兼容），原样返回
        return chineseType;
    }

    /** 英文枚举名 → 中文 display（写入 type 字段时用） */
    private static String enumNameToType(String englishName) {
        if (englishName == null || englishName.isEmpty()) return null;
        try {
            SubjectCategory cat = SubjectCategory.valueOf(englishName.trim().toUpperCase());
            return cat.display;
        } catch (IllegalArgumentException e) {
            // 兜底：当成中文使用
            return englishName;
        }
    }

    private static JsonResult failureWithType(String msg, String errorType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorType", errorType);
        return JsonResult.failure(msg).setData(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // P1-4 创建末级科目
    // POST /openapi/v1/subjects
    // ═══════════════════════════════════════════════════════════════

    /**
     * 在指定父科目下创建一个末级科目（如：1122 应收账款 下挂"应收账款_新客户XYZ" 112237）。
     * 业务约束（沿用内部 SubjectController.save 的逻辑）：
     *  - 父科目必须存在且属于该账套
     *  - 同账套下 code 唯一（由 SubjectService.save 内部 unique key 保证）
     *  - 自动继承父科目的 type / balanceDirection（除非显式指定）
     */
    @PostMapping
    @Transactional
    public JsonResult create(@RequestBody CreateSubjectReq req) {
        if (req.getAccountSetsId() == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (StrUtil.isBlank(req.getCode())) {
            return JsonResult.failure("code 不能为空");
        }
        if (StrUtil.isBlank(req.getName())) {
            return JsonResult.failure("name 不能为空");
        }
        if (req.getParentId() == null || req.getParentId() == 0L) {
            return JsonResult.failure("parentId 不能为空（OpenAPI 仅支持在已有科目下创建末级科目，不支持新建一级科目）");
        }

        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(req.getAccountSetsId()))
                .fetchFirst();
        if (accountSets == null) {
            return JsonResult.failure("账套不存在：" + req.getAccountSetsId());
        }

        Subject parent = subjectService.getById(new PrimaryKey(req.getParentId(), req.getAccountSetsId()));
        if (parent == null) {
            return failureWithType("父科目不存在：" + req.getParentId(), "PARENT_NOT_FOUND");
        }

        // 校验 code 必须以父科目 code 为前缀（财务系统约定）
        if (!req.getCode().startsWith(parent.getCode())) {
            return failureWithType("code 必须以父科目 code 为前缀（父=" + parent.getCode() + "，传入=" + req.getCode() + "）",
                    "INVALID_CODE");
        }

        // 同账套查重
        Subject existing = subjectService.listByCode(req.getAccountSetsId(), req.getCode());
        if (existing != null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("errorType", "DUPLICATE_CODE");
            data.put("existingId", existing.getId());
            data.put("existingName", existing.getName());
            return JsonResult.failure("科目编码已存在：" + req.getCode()).setData(data);
        }

        Subject entity = new Subject();
        entity.setAccountSetsId(req.getAccountSetsId());
        entity.setCode(req.getCode().trim());
        entity.setName(req.getName().trim());
        entity.setParentId(req.getParentId());
        // 类型 / 余额方向 默认继承父科目
        entity.setType(StrUtil.isNotBlank(req.getType())
                ? enumNameToType(req.getType())
                : parent.getType());
        entity.setBalanceDirection(StrUtil.isNotBlank(req.getBalanceDirection())
                ? req.getBalanceDirection()
                : parent.getBalanceDirection());
        entity.setLevel(parent.getLevel() + 1);
        entity.setLeaf(true);
        entity.setStatus(req.getStatus() == null || Boolean.TRUE.equals(req.getStatus()));
        entity.setSystemDefault(false);

        try {
            // encLen 为科目编码总长度之和（与内部 SubjectController.save 一致）
            Integer encLen = accountSets.getEncoding() != null
                    ? accountSets.getEncoding().stream().reduce(Integer::sum).orElse(0)
                    : req.getCode().length();
            Subject saved = subjectService.save(entity, encLen);
            return JsonResult.successful(toDto(saved));
        } catch (Exception e) {
            log.warn("openapi 创建科目失败: code={}, name={}, msg={}", req.getCode(), req.getName(), e.getMessage());
            return JsonResult.failure("创建失败：" + e.getMessage());
        }
    }

    /** 中文 type → 英文枚举名（toDto 用） */
    private Map<String, Object> toDto(Subject s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("accountSetsId", s.getAccountSetsId());
        m.put("code", s.getCode());
        m.put("name", s.getName());
        m.put("fullName", s.getFullName());
        m.put("type", typeToEnumName(s.getType()));
        m.put("balanceDirection", s.getBalanceDirection());
        m.put("parentId", s.getParentId());
        m.put("level", s.getLevel());
        m.put("leaf", Boolean.TRUE.equals(s.getLeaf()));
        m.put("status", Boolean.TRUE.equals(s.getStatus()));
        m.put("cash", Boolean.TRUE.equals(s.getCash()));
        m.put("unit", s.getUnit());
        m.put("mnemonicCode", s.getMnemonicCode());
        m.put("queryCode", s.getQueryCode());

        // 必填辅助核算 = auxiliaryAccounting - nonAuxiliaryAccounting
        List<Long> aux = s.getAuxiliaryAccounting() != null ? s.getAuxiliaryAccounting() : Collections.emptyList();
        List<Long> nonAux = s.getNonAuxiliaryAccounting() != null ? s.getNonAuxiliaryAccounting() : Collections.emptyList();
        List<Long> required = new ArrayList<>();
        for (Long id : aux) {
            if (!nonAux.contains(id)) {
                required.add(id);
            }
        }
        m.put("auxiliaryAccountingRequired", required);
        m.put("auxiliaryAccountingOptional", nonAux);
        m.put("currencyAccounting", s.getCurrencyAccounting() != null ? s.getCurrencyAccounting() : Collections.emptyList());

        return m;
    }

    @Data
    public static class CreateSubjectReq {
        private Long accountSetsId;
        private String code;
        private String name;
        private Long parentId;
        private String type;              // 枚举名：ASSET/LIABILITY/...，不传则继承父科目
        private String balanceDirection;  // 借/贷，不传则继承父科目
        private Boolean status;           // 默认 true
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-NEW-OPENAPI-ENSURE-SUBJECTS — 做账前科目预检 + 缺科目按规则自动补建
    // POST /openapi/v1/subjects/ensure
    // ═══════════════════════════════════════════════════════════════

    // FN-NEW-OPENAPI-ENSURE-SUBJECTS 审查反馈（2026-05-22）：
    // 原方法整体标 @Transactional → 单条 testSaveSubject 抛 DataIntegrityViolationException 等
    // 非 EnsureException 异常时，事务被标记 rollback-only 但循环继续 → 返回「假成功」、事务实际回滚。
    // 修复：去掉外层 @Transactional，让每条 testSaveSubject 走自身 @Transactional 独立事务
    //      （单条失败回滚单条、不污染其他条）；catch 改成捕获所有 RuntimeException + 记录详细日志。
    @PostMapping("/ensure")
    public JsonResult ensure(@RequestBody EnsureReq req) {
        if (req == null) return JsonResult.failure("请求体不能为空");
        if (req.getAccountSetsId() == null) return JsonResult.failure("accountSetsId 不能为空");
        if (CollUtil.isEmpty(req.getRequired())) return JsonResult.failure("required 不能为空");

        AccountSets accountSets = findAccountSet(req.getAccountSetsId());
        if (accountSets == null) return JsonResult.failure("账套不存在：" + req.getAccountSetsId());
        int encLen = encLen(accountSets);

        List<Map<String, Object>> subjects = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        int createdCount = 0;
        int pendingConfirmCount = 0;

        for (RequiredItem item : req.getRequired()) {
            try {
                Map<String, Object> r = "detail".equalsIgnoreCase(item.getKind())
                        ? ensureDetail(accountSets, item, encLen)
                        : ensureStandard(accountSets, item, encLen);
                subjects.add(r);
                if (Boolean.FALSE.equals(r.get("existed"))) createdCount++;
                if (Boolean.TRUE.equals(r.get("autoCreated"))) pendingConfirmCount++;
            } catch (EnsureException e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("key", item.getKey());
                err.put("errorType", e.errorType);
                err.put("message", e.getMessage());
                errors.add(err);
            } catch (RuntimeException e) {
                // 兜底捕获 DB 唯一键冲突 / 连接失败等非业务异常，避免事务回滚但调用方收「假成功」。
                // 单条失败 → 走 testSaveSubject 自身 @Transactional 回滚 → 其他条不受影响。
                log.error("[FN-NEW-OPENAPI-ENSURE-SUBJECTS] key={} kind={} 建科目失败 {}: {}",
                        item.getKey(), item.getKind(), e.getClass().getSimpleName(), e.getMessage(), e);
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("key", item.getKey());
                err.put("errorType", "UNEXPECTED_ERROR");
                err.put("message", e.getClass().getSimpleName() + ": " + e.getMessage());
                errors.add(err);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subjects", subjects);
        data.put("createdCount", createdCount);
        data.put("pendingConfirmCount", pendingConfirmCount);
        data.put("errors", errors);
        return JsonResult.successful(data);
    }

    /** 标准科目：先按编码查、再按名称兜底；缺则按标准建（auto_created=0，结构固定无需确认） */
    private Map<String, Object> ensureStandard(AccountSets accountSets, RequiredItem item, int encLen) {
        StdSubject std = STANDARD.get(item.getKey());
        if (std == null) {
            throw new EnsureException("UNKNOWN_STANDARD_KEY", "未知标准科目业务代码：" + item.getKey());
        }
        Long accountSetsId = accountSets.getId();
        Subject existing = subjectService.listByCode(accountSetsId, std.code());
        if (existing == null) existing = findByName(accountSetsId, std.name());
        if (existing != null) {
            return subjectRow(item.getKey(), existing, true);
        }
        Subject created = new Subject();
        created.setAccountSetsId(accountSetsId);
        created.setCode(std.code());
        created.setName(std.name());
        created.setType(std.type());
        created.setBalanceDirection(std.direction());
        created.setParentId(0L);
        created.setLevel(1);
        created.setLeaf(true);
        created.setStatus(true);
        created.setSystemDefault(false);
        created.setAutoCreated(false);
        Subject saved = subjectService.testSaveSubject(created, encLen);
        return subjectRow(item.getKey(), saved, false);
    }

    /** 明细科目：父下顺延末级编码建（auto_created=1 待确认），继承父科目辅助核算 */
    private Map<String, Object> ensureDetail(AccountSets accountSets, RequiredItem item, int encLen) {
        Long accountSetsId = accountSets.getId();
        if (StrUtil.isBlank(item.getParentCode())) {
            throw new EnsureException("PARENT_CODE_REQUIRED", "明细科目必须传 parentCode：" + item.getKey());
        }
        if (StrUtil.isBlank(item.getDetailName())) {
            throw new EnsureException("DETAIL_NAME_REQUIRED", "明细科目必须传 detailName：" + item.getKey());
        }
        Subject parent = subjectService.listByCode(accountSetsId, item.getParentCode());
        if (parent == null) {
            throw new EnsureException("PARENT_NOT_FOUND", "父科目不存在：" + item.getParentCode());
        }
        Subject existingChild = findChildByName(accountSetsId, parent.getId(), item.getDetailName());
        if (existingChild != null) {
            return subjectRow(item.getKey(), existingChild, true);
        }
        boolean parentIsLeaf = childrenCount(accountSetsId, parent.getId()) == 0;
        if (parentIsLeaf && Boolean.TRUE.equals(subjectService.checkHasData(parent.getId(), accountSetsId))) {
            throw new EnsureException("PARENT_IS_LEAF",
                    "父科目 " + item.getParentCode() + " 已直接挂余额/凭证，不能自动加明细，请会计人工处理");
        }
        Subject child = new Subject();
        child.setAccountSetsId(accountSetsId);
        child.setCode(nextChildCode(accountSets, parent));
        child.setName(item.getDetailName());
        child.setType(parent.getType());
        child.setBalanceDirection(parent.getBalanceDirection());
        child.setParentId(parent.getId());
        child.setLevel((parent.getLevel() == null ? 1 : parent.getLevel()) + 1);
        child.setLeaf(true);
        child.setStatus(true);
        child.setSystemDefault(false);
        child.setAutoCreated(true);
        // 辅助核算：testSaveSubject 把父科目辅助核算下传给首个子级（见 SubjectService line 320），无需手动设
        Subject saved = subjectService.testSaveSubject(child, encLen);
        return subjectRow(item.getKey(), saved, false);
    }

    /** 父科目下一个可用末级编码：max 子编码 +1；无子时父编码 + 段长零填充的 1 */
    private String nextChildCode(AccountSets accountSets, Subject parent) {
        String maxChild = subjectService.getMaxChildCode(parent.getId(), accountSets.getId());
        int segLen = childSegLen(accountSets, parent.getLevel());
        String parentCode = parent.getCode();
        if (StrUtil.isBlank(maxChild) || maxChild.length() <= parentCode.length()) {
            return parentCode + String.format("%0" + segLen + "d", 1);
        }
        String seg = maxChild.substring(parentCode.length());
        long next = 1;
        try { next = Long.parseLong(seg) + 1; } catch (NumberFormatException ignore) {}
        return parentCode + String.format("%0" + segLen + "d", next);
    }

    /** 子级段长度：父 level（1-based）对应 encoding 的下一段（index=parentLevel），缺省 2 */
    private int childSegLen(AccountSets accountSets, Integer parentLevel) {
        int lvl = parentLevel == null ? 1 : parentLevel;
        if (accountSets.getEncoding() != null && accountSets.getEncoding().size() > lvl) {
            return accountSets.getEncoding().get(lvl);
        }
        return 2;
    }

    private static int encLen(AccountSets accountSets) {
        int n = 0;
        if (accountSets.getEncoding() != null) {
            for (Integer seg : accountSets.getEncoding()) n += seg;
        }
        return n;
    }

    private static Map<String, Object> subjectRow(String key, Subject s, boolean existed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("subjectId", s.getId());
        m.put("code", s.getCode());
        m.put("name", s.getName());
        m.put("existed", existed);
        m.put("autoCreated", Boolean.TRUE.equals(s.getAutoCreated()));
        return m;
    }

    // ── bqf 查询（package-private 便于单测 override） ──

    AccountSets findAccountSet(Long accountSetsId) {
        return bqf.selectFrom(qAccountSets).where(qAccountSets.id.eq(accountSetsId)).fetchFirst();
    }

    Subject findByName(Long accountSetsId, String name) {
        return bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.name.eq(name))
                        .and(qSubject.status.isTrue()))
                .orderBy(qSubject.code.asc())
                .fetchFirst();
    }

    Subject findChildByName(Long accountSetsId, Long parentId, String name) {
        return bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.parentId.eq(parentId))
                        .and(qSubject.name.eq(name)))
                .fetchFirst();
    }

    long childrenCount(Long accountSetsId, Long parentId) {
        return bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId).and(qSubject.parentId.eq(parentId)))
                .fetchCount();
    }

    public record StdSubject(String code, String name, String type, String direction) {}

    static class EnsureException extends RuntimeException {
        final String errorType;
        EnsureException(String errorType, String message) {
            super(message);
            this.errorType = errorType;
        }
    }

    @Data
    public static class EnsureReq {
        private Long accountSetsId;
        private List<RequiredItem> required;
    }

    @Data
    public static class RequiredItem {
        private String key;            // standard: 业务含义代码；detail: 自定义标识（仅回显）
        private String kind;           // standard / detail
        private String parentCode;     // detail: 父科目编码
        private String detailName;     // detail: 明细名称
        private String auxiliaryType;  // detail: 辅助核算类型（目前继承父科目设置）
    }
}
