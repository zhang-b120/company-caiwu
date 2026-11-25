package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.enums.AccountStandard;
import cn.gson.financial.kernel.model.enums.SubjectCategory;
import cn.gson.financial.kernel.model.enums.TaxpayerType;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.service.AccountSetsService;
import cn.gson.financial.security.OpenApiSecurityGuards;
import cn.hutool.core.util.StrUtil;
import com.querydsl.core.BooleanBuilder;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * 开放 API — 账套
 * <p>
 * 提供给外部记账系统（xiaowei-jizhang 等）使用，全部接口免鉴权（@SaIgnore）。
 * 路径前缀 /openapi/v1/account-sets
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/account-sets")
@SaIgnore
public class OpenApiAccountSetsController {

    private final AccountSetsService accountSetsService;
    // FN-COLLECT-MODE-PUT-405：PUT 更新单字段用 JPA repo 直存（避开 create 模板复制 / update() 读 enableDate 的副作用）
    private final cn.gson.financial.kernel.model.repo.AccountSetsRepository accountSetsRepository;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    /**
     * FN-SEC-T1（167）安全门面：lookup 连续失败锁 IP（防撞库）+ 审计。
     * {@code required=false} —— 纯 JUnit 单测无 Spring 上下文时为 null，控制器走原路径不受影响。
     */
    @Autowired(required = false)
    private OpenApiSecurityGuards securityGuards;

    private static final QAccountSets qAccountSets = QAccountSets.accountSets;

    // ═══════════════════════════════════════════════════════════════
    // §1.1 模糊搜账套
    // ═══════════════════════════════════════════════════════════════

    /**
     * 模糊搜索账套
     * GET /openapi/v1/account-sets/search?keyword=上海&limit=10
     */
    @GetMapping("/search")
    public JsonResult search(@RequestParam(required = false) String keyword,
                             @RequestParam(required = false, defaultValue = "10") Integer limit,
                             // FN-PROGRESS-SCOPE-BY-OWNER：传 owner_user_id 时按登录账号归属过滤（与 progress/pipeline 同口径）
                             @RequestParam(value = "owner_user_id", required = false) Long ownerUserId) {
        if (limit == null || limit <= 0) {
            limit = 10;
        }
        if (limit > 50) {
            limit = 50;
        }

        // 按登录账号归属：复用 findActiveByUserAndFilters（与首页/任务工作台同口径）；keyword 透传
        if (ownerUserId != null) {
            List<AccountSets> owned = accountSetsRepository.findActiveByUserAndFilters(
                    ownerUserId, StrUtil.isBlank(keyword) ? null : keyword.trim(), null, null);
            List<AccountSets> limited = owned.size() > limit ? owned.subList(0, limit) : owned;
            List<Map<String, Object>> scoped = new ArrayList<>();
            for (AccountSets a : limited) scoped.add(toBriefDto(a));
            return JsonResult.successful(scoped);
        }

        BooleanBuilder where = new BooleanBuilder();
        where.and(qAccountSets.deactivated.isNull().or(qAccountSets.deactivated.isFalse()));
        if (StrUtil.isNotBlank(keyword)) {
            where.and(qAccountSets.companyName.containsIgnoreCase(keyword.trim()));
        }

        // 两步查询：先取 ID 列表（带 limit），再用 ID 批量加载完整 entity。
        // 这样跟 §1.2 detail 接口走同一份"按 PK 查实体"的路径，确保 currentAccountDate
        // 等字段在两个接口里始终一致；规避 BlazeJPA 在 limit+orderBy 时偶发使用 projection
        // 加载导致的字段不一致（参考 BUG C-1）。
        List<Long> ids = bqf.selectFrom(qAccountSets)
                .select(qAccountSets.id)
                .where(where)
                .orderBy(qAccountSets.id.desc())
                .limit(limit)
                .fetch();

        List<AccountSets> rows = ids.isEmpty() ? Collections.emptyList()
                : bqf.selectFrom(qAccountSets)
                        .where(qAccountSets.id.in(ids))
                        .orderBy(qAccountSets.id.desc())
                        .fetch();

        List<Map<String, Object>> result = new ArrayList<>();
        for (AccountSets a : rows) {
            result.add(toBriefDto(a));
        }
        return JsonResult.successful(result);
    }

    // ═══════════════════════════════════════════════════════════════
    // §1.2 查询账套详情
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/{id}")
    public JsonResult detail(@PathVariable Long id) {
        AccountSets a = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(id))
                .fetchFirst();
        if (a == null) {
            return JsonResult.failure("账套不存在：" + id);
        }
        return JsonResult.successful(toFullDto(a));
    }

    // ═══════════════════════════════════════════════════════════════
    // §1.3 更新账套配置（发票采集模式）—— FN-COLLECT-MODE-PUT-405
    // ═══════════════════════════════════════════════════════════════

    /**
     * PUT /openapi/v1/account-sets/{id}  body {invoiceCollectMode: QUARTERLY|MONTHLY}
     * <p>BT 切换发票采集模式（季度/月度）落库。原工单只暴露读、漏写端点 → BT PUT 撞 405，本端点补上。
     * 仅更新 invoice_collect_mode 单字段（JPA repo 直存，无 create 模板复制副作用）。
     */
    @PutMapping("/{id}")
    @Transactional
    public JsonResult update(@PathVariable Long id, @RequestBody UpdateAccountSetReq req) {
        if (req == null || StrUtil.isBlank(req.getInvoiceCollectMode())) {
            return JsonResult.failure("invoiceCollectMode 不能为空（QUARTERLY/MONTHLY）", 400);
        }
        cn.gson.financial.kernel.model.enums.InvoiceCollectMode mode;
        try {
            mode = cn.gson.financial.kernel.model.enums.InvoiceCollectMode.valueOf(
                    req.getInvoiceCollectMode().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return JsonResult.failure("invoiceCollectMode 非法（仅 QUARTERLY/MONTHLY）：" + req.getInvoiceCollectMode(), 400);
        }
        AccountSets a = accountSetsRepository.findById(id).orElse(null);
        if (a == null) {
            return JsonResult.failure("账套不存在：" + id, 404);
        }
        a.setInvoiceCollectMode(mode);
        accountSetsRepository.save(a);
        log.info("[FN-COLLECT-MODE-PUT-405] 账套 {} 发票采集模式 → {}", id, mode);
        return JsonResult.successful(toFullDto(a));
    }

    // ═══════════════════════════════════════════════════════════════
    // §3.1 创建账套
    // ═══════════════════════════════════════════════════════════════

    /**
     * 创建账套（自动复制模板科目树）
     * POST /openapi/v1/account-sets
     */
    @PostMapping
    @Transactional
    public JsonResult create(@RequestBody CreateAccountSetReq req) {
        if (StrUtil.isBlank(req.getCompanyName())) {
            return JsonResult.failure("companyName 不能为空");
        }
        if (StrUtil.isBlank(req.getCreditCode())) {
            return JsonResult.failure("creditCode 不能为空");
        }
        if (req.getEnableDate() == null) {
            return JsonResult.failure("enableDate 不能为空");
        }
        if (!req.getEnableDate().isAfter(LocalDate.of(2009, 12, 31))) {
            return JsonResult.failure("enableDate 必须大于 2009-12-31");
        }

        // creditCode 唯一性检查
        AccountSets existing = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.creditCode.eq(req.getCreditCode()))
                .fetchFirst();
        if (existing != null) {
            return JsonResult.failure("creditCode 已存在：" + req.getCreditCode() + "（账套 ID=" + existing.getId() + "）");
        }

        try {
            AccountSets entity = new AccountSets();
            entity.setCompanyName(req.getCompanyName().trim());
            entity.setCreditCode(req.getCreditCode().trim());
            entity.setEnableDate(req.getEnableDate());
            entity.setCurrentAccountDate(req.getEnableDate());
            entity.setEffectiveDate(LocalDate.now().plusYears(1000));

            // 会计准则
            if (StrUtil.isNotBlank(req.getAccountStandard())) {
                entity.setAccountStandard(AccountStandard.valueOf(req.getAccountStandard().trim()));
            } else {
                entity.setAccountStandard(AccountStandard.SMALL_BUSINESS);
            }

            // 增值税类型
            if (StrUtil.isNotBlank(req.getVatType())) {
                entity.setVatType(VatType.valueOf(req.getVatType().trim()));
            } else {
                entity.setVatType(VatType.SMALL_SCALE);
            }

            // 科目编码层级
            if (req.getEncoding() != null && !req.getEncoding().isEmpty()) {
                entity.setEncoding(req.getEncoding());
            } else {
                entity.setEncoding(Arrays.asList(4, 2, 2));
            }

            // 启用的科目类别
            if (req.getSubjectCategory() != null && !req.getSubjectCategory().isEmpty()) {
                List<SubjectCategory> cats = new ArrayList<>();
                for (String c : req.getSubjectCategory()) {
                    cats.add(SubjectCategory.valueOf(c));
                }
                entity.setSubjectCategory(cats);
            } else {
                entity.setSubjectCategory(Arrays.asList(
                        SubjectCategory.ASSET,
                        SubjectCategory.LIABILITY,
                        SubjectCategory.RIGHTS,
                        SubjectCategory.COST,
                        SubjectCategory.INCOME
                ));
            }

            // 选填字段
            entity.setProvince(req.getProvince());
            entity.setIndustryCode(req.getIndustryCode());
            entity.setIndustryName(req.getIndustryName());
            entity.setIndividualBusiness(Boolean.TRUE.equals(req.getIndividualBusiness()));
            entity.setVoucherReviewed(Boolean.TRUE.equals(req.getVoucherReviewed()));
            entity.setAiProfile(req.getAiProfile());
            entity.setRemark(req.getRemark());
            entity.setAstId(req.getAstId());
            entity.setZeroDeclarations(false);
            entity.setSyncType("openapi");

            accountSetsService.save(entity, true);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", entity.getId());
            data.put("companyName", entity.getCompanyName());
            data.put("currentAccountDate", entity.getCurrentAccountDate());
            return JsonResult.successful(data);
        } catch (IllegalArgumentException e) {
            return JsonResult.failure("枚举值非法：" + e.getMessage());
        } catch (Exception e) {
            log.error("openapi 账套创建失败", e);
            return JsonResult.failure(e.getMessage() != null ? e.getMessage() : "创建账套失败");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════════

    private Map<String, Object> toBriefDto(AccountSets a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("companyName", a.getCompanyName());
        m.put("creditCode", a.getCreditCode());
        m.put("accountStandard", a.getAccountStandard() != null ? a.getAccountStandard().name() : null);
        m.put("vatType", a.getVatType() != null ? a.getVatType().name() : null);
        // FN-INVOICE-COLLECT-MODE-CONFIG：发票采集模式（QUARTERLY/MONTHLY；null→QUARTERLY 默认）
        m.put("invoiceCollectMode", a.getInvoiceCollectMode() != null ? a.getInvoiceCollectMode().name() : "QUARTERLY");
        m.put("currentAccountDate", a.getCurrentAccountDate());
        m.put("deactivated", Boolean.TRUE.equals(a.getDeactivated()));
        return m;
    }

    private Map<String, Object> toFullDto(AccountSets a) {
        Map<String, Object> m = toBriefDto(a);
        m.put("enableDate", a.getEnableDate());
        m.put("voucherReviewed", Boolean.TRUE.equals(a.getVoucherReviewed()));
        m.put("encoding", a.getEncoding());
        m.put("province", a.getProvince());
        m.put("address", a.getAddress());
        m.put("industryCode", a.getIndustryCode());
        m.put("industryName", a.getIndustryName());
        m.put("individualBusiness", Boolean.TRUE.equals(a.getIndividualBusiness()));
        m.put("cashierModule", Boolean.TRUE.equals(a.getCashierModule()));
        m.put("fixedAssetModule", Boolean.TRUE.equals(a.getFixedAssetModule()));
        m.put("aiProfile", a.getAiProfile());
        m.put("remark", a.getRemark());
        m.put("subjectCategory", a.getSubjectCategory());
        // FN-FIX-10：派生纳税人类型 — 权威枚举 SMALL_SCALE / GENERAL / INDIVIDUAL
        TaxpayerType taxpayer = TaxpayerType.fromAccountSets(a.getIndividualBusiness(), a.getVatType());
        m.put("taxpayerType", taxpayer.name());
        m.put("taxpayerVaultTag", taxpayer.vaultTag);  // 给 KR 检索 OB Vault 用的中文 tag
        return m;
    }

    // ═══════════════════════════════════════════════════════════════
    // §1.3 按公司名称 + 电税密码查询账套
    // ═══════════════════════════════════════════════════════════════

    /**
     * 按公司名称和电税密码查询账套
     * POST /openapi/v1/account-sets/lookup-by-credentials
     * 入参含密码，使用 POST 避免明文出现在访问日志
     */
    @PostMapping("/lookup-by-credentials")
    public JsonResult lookupByCredentials(@RequestBody LookupByCredentialsReq req) {
        if (StrUtil.isBlank(req.getCompanyName())) {
            return JsonResult.failure("companyName 不能为空");
        }
        if (StrUtil.isBlank(req.getElectricityTaxCode())) {
            return JsonResult.failure("electricityTaxCode 不能为空");
        }

        AccountSets a = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.deactivated.isNull().or(qAccountSets.deactivated.isFalse()))
                .where(qAccountSets.companyName.eq(req.getCompanyName().trim()))
                .where(qAccountSets.electricityTaxCode.eq(req.getElectricityTaxCode().trim()))
                .fetchFirst();

        // FN-SEC-T1（167）防撞库：命中→清零连续失败；未命中→失败计数+1（达阈值锁该 IP 30min）。同时审计。
        // 仅校验格式通过后的真实查询计入，杜绝把电税账号写进任何日志（仅传公司名供取证）。
        if (securityGuards != null) {
            securityGuards.onLookupResult(a != null, req.getCompanyName());
        }

        if (a == null) {
            return JsonResult.failure("该企业查询不到");
        }
        // FN-ID-T1：JS 端（收银端 146）雪花 id 精度丢失修复 —— 仅本接口把 id 覆盖为字符串；
        // toBriefDto/search/{id} 不动（xiaowei-jizhang 按数字 id 处理）。
        Map<String, Object> dto = toFullDto(a);
        dto.put("id", String.valueOf(a.getId()));
        return JsonResult.successful(dto);
    }

    // ═══════════════════════════════════════════════════════════════
    // 请求 DTO
    // ═══════════════════════════════════════════════════════════════

    @Data
    public static class UpdateAccountSetReq {
        /** 发票采集模式：QUARTERLY / MONTHLY */
        private String invoiceCollectMode;
    }

    @Data
    public static class CreateAccountSetReq {
        private String companyName;
        private String creditCode;
        private String accountStandard;
        private String vatType;
        private LocalDate enableDate;
        private List<Integer> encoding;
        private List<String> subjectCategory;
        private String province;
        private String industryName;
        private String industryCode;
        private Boolean individualBusiness;
        private Boolean voucherReviewed;
        private String aiProfile;
        private String remark;
        private Long astId;
    }

    @Data
    public static class LookupByCredentialsReq {
        private String companyName;
        private String electricityTaxCode;
    }
}
