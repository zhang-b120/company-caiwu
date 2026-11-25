package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.dto.VoucherDetailsDto;
import cn.gson.financial.kernel.model.dto.VoucherDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.entity.QAssetsChange;
import cn.gson.financial.kernel.model.entity.QAssetsDepreciation;
import cn.gson.financial.kernel.model.entity.QAssetsDetail;
import cn.gson.financial.kernel.model.entity.QBankStatementDetail;
import cn.gson.financial.kernel.model.entity.QInvoice;
import cn.gson.financial.kernel.model.entity.QSalary;
import cn.gson.financial.kernel.model.entity.QSalaryVoucher;
import cn.gson.financial.kernel.model.entity.QSalaryVoucherTemplate;
import cn.gson.financial.kernel.model.entity.QVoucher;
import cn.gson.financial.kernel.model.entity.QVoucherWord;
import cn.gson.financial.kernel.model.entity.SalaryVoucher;
import cn.gson.financial.kernel.model.entity.SalaryVoucherTemplate;
import cn.gson.financial.kernel.model.entity.Voucher;
import cn.gson.financial.kernel.model.entity.VoucherAuditLog;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.entity.QVoucherDetails;
import cn.gson.financial.kernel.model.entity.QSubject;
import cn.gson.financial.kernel.model.entity.VoucherDetails;
import cn.gson.financial.kernel.model.repo.SalaryVoucherRepository;
import cn.gson.financial.kernel.model.repo.SalaryVoucherTemplateRepository;
import cn.gson.financial.kernel.service.BalanceCalcService;
import cn.gson.financial.kernel.service.CheckoutService;
import cn.gson.financial.kernel.service.VoucherService;
import cn.hutool.core.util.StrUtil;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 开放 API — 凭证（查询 / 创建 / 批量创建）
 * 路径前缀 /openapi/v1/voucher
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/voucher")
@SaIgnore
public class OpenApiVoucherController {

    private final VoucherService voucherService;
    private final cn.gson.financial.kernel.model.repo.VoucherAuditLogRepository auditLogRepository;
    // FN-FIX-VOUCHER-DELETE-CASCADE: clear-period 一键清空 OpenAPI 用
    private final BalanceCalcService balanceCalcService;
    private final CheckoutService checkoutService;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    @Resource
    private JPAQueryFactory jqf;

    // FN-API-GAPS ③：做账前置完整性检查复用现有完整度服务（字段注入，不动既有构造签名）
    @Resource
    private cn.gson.financial.service.VoucherCompletenessService voucherCompletenessService;

    // FN-NEW-SOURCE-IDS-FULL: salary_voucher 关联表 + SalaryVoucherTemplate 兜底创建
    @Resource
    private SalaryVoucherRepository salaryVoucherRepository;

    @Resource
    private SalaryVoucherTemplateRepository salaryVoucherTemplateRepository;

    private static final QAccountSets qAccountSets = QAccountSets.accountSets;
    private static final QVoucher qVoucher = QVoucher.voucher;
    private static final QInvoice qInvoice = QInvoice.invoice;
    private static final QBankStatementDetail qBsd = QBankStatementDetail.bankStatementDetail;
    private static final QVoucherDetails qVoucherDetails = QVoucherDetails.voucherDetails;
    private static final QSubject qSubject = QSubject.subject;
    // FN-NEW-SOURCE-IDS-FULL: salary / 固定资产 三表 source ID 回写所需 Q 实体
    private static final QSalary qSalary = QSalary.salary;
    private static final QSalaryVoucher qSalaryVoucher = QSalaryVoucher.salaryVoucher;
    private static final QSalaryVoucherTemplate qSalaryVoucherTemplate = QSalaryVoucherTemplate.salaryVoucherTemplate;
    private static final QVoucherWord qVoucherWord = QVoucherWord.voucherWord;
    private static final QAssetsDepreciation qAssetsDepreciation = QAssetsDepreciation.assetsDepreciation;
    private static final QAssetsDetail qAssetsDetail = QAssetsDetail.assetsDetail;
    private static final QAssetsChange qAssetsChange = QAssetsChange.assetsChange;

    // ═══════════════════════════════════════════════════════════════
    // §2.9 已有凭证查询
    // ═══════════════════════════════════════════════════════════════

    /**
     * POST /openapi/v1/voucher/list
     */
    @PostMapping("/list")
    public JsonResult list(@RequestBody VoucherListReq req) {
        if (req.getAccountSetsId() == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        Integer page = req.getPage() != null ? req.getPage() : 1;
        Integer size = req.getSize() != null ? req.getSize() : 100;
        if (page < 1) page = 1;
        if (size < 1) size = 100;
        if (size > 500) size = 500;

        VoucherService.VoucherQuery query = new VoucherService.VoucherQuery();
        query.setAccountSetsId(req.getAccountSetsId());
        // period="2025-12" 优先于单独传的 year/month；不传时保持原有行为（全量或按 year/month 过滤）
        if (StrUtil.isNotBlank(req.getPeriod())) {
            try {
                java.time.YearMonth ym = java.time.YearMonth.parse(req.getPeriod());
                query.setYear(ym.getYear());
                query.setMonth(ym.getMonthValue());
            } catch (Exception e) {
                return JsonResult.failure("period 格式非法（应为 YYYY-MM）：" + req.getPeriod());
            }
        } else {
            if (req.getYear() != null) query.setYear(req.getYear());
            if (req.getMonth() != null) query.setMonth(req.getMonth());
        }
        if (StrUtil.isNotBlank(req.getWord())) query.setWord(req.getWord());
        // BUG B-4：voucher.remark 是 LONGTEXT，Hibernate 6 严格模式下不能直接 LIKE。
        // 避开 query.setRemark(...)（内部用 qVoucher.remark.contains 会触发类型错误），
        // 改为直接往 query.builder 拼 cast 表达式。
        // Hibernate 6 HQL 的 cast 只认枚举类型 string/character/integer/... — 不能写 char(2000)。
        if (StrUtil.isNotBlank(req.getRemarkContains())) {
            query.builder.and(
                    Expressions.booleanTemplate(
                            "cast({0} as string) like {1}",
                            qVoucher.remark,
                            "%" + req.getRemarkContains() + "%"
                    )
            );
        }

        Page p = new Page();
        p.setPage(page);
        p.setPageSize(size);
        // VoucherService.getOrderSpecifiers 用 ReflectUtil.invoke(field, page.getOrder()) 做排序，
        // order 为空会抛 "Method name must be not blank!" —— 给一个安全默认（最近优先）
        p.setOrder("desc");
        p.setProperty("voucherDate");

        PageResults<VoucherDto> pr = voucherService.page(query, p);
        Collection<VoucherDto> results = pr.getResults() != null ? pr.getResults() : Collections.emptyList();

        // FN-FIX-DB-HEALTH-AND-HOTPATH §3：voucherService.page() 内 fetchAuxiliaryDetail 已批量加载每张凭证的 details，
        // 直接复用 v.getDetails()，去掉原 includeDetails 的第 2 次 voucher_details 查询（凭证列表冗余查询，热点收口）。
        boolean includeDetails = Boolean.TRUE.equals(req.getIncludeDetails());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (VoucherDto v : results) {
            Map<String, Object> m = toBriefDto(v);
            if (includeDetails) {
                List<Map<String, Object>> details = new ArrayList<>();
                if (v.getDetails() != null) {
                    for (cn.gson.financial.kernel.model.dto.VoucherDetailsDto d : v.getDetails()) {
                        Map<String, Object> dm = new LinkedHashMap<>();
                        dm.put("id", d.getId());
                        dm.put("subjectId", d.getSubjectId());
                        dm.put("subjectCode", d.getSubjectCode());
                        dm.put("subjectName", d.getSubjectName());
                        dm.put("summary", d.getSummary());
                        dm.put("debitAmount", d.getDebitAmount());
                        dm.put("creditAmount", d.getCreditAmount());
                        dm.put("auxiliaryDetailId", d.getAuxiliaryDetailId());
                        dm.put("currencyId", d.getCurrencyId());
                        dm.put("num", d.getNum());
                        dm.put("price", d.getPrice());
                        dm.put("cashFlow", Boolean.TRUE.equals(d.getCashFlow()));
                        details.add(dm);
                    }
                }
                m.put("details", details);
            }
            rows.add(m);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", rows);
        // 用 int 包装绕过全局 Long→String 序列化器（WebMvcSupport.java:183），保证 total 是 number
        data.put("total", (int) pr.getTotal());
        data.put("page", page);
        data.put("size", size);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // §3.4 创建凭证
    // ═══════════════════════════════════════════════════════════════

    /**
     * POST /openapi/v1/voucher
     */
    @PostMapping
    @Transactional
    public JsonResult create(@RequestBody VoucherCreateReq req) {
        if (req.getAccountSetsId() == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        AccountSets accountSets = findAccountSetForTest(req.getAccountSetsId());
        if (accountSets == null) {
            return JsonResult.failure("账套不存在：" + req.getAccountSetsId());
        }

        try {
            VoucherDto saved = saveSingle(req, accountSets);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", saved.getId());
            data.put("code", saved.getCode());
            data.put("word", saved.getWord());
            data.put("voucherDate", saved.getVoucherDate());
            return JsonResult.successful(data);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    /** package-private test hook：单测覆写以避开 BlazeJPAQuery 难 mock 的问题 */
    AccountSets findAccountSetForTest(Long accountSetsId) {
        return bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(accountSetsId))
                .fetchFirst();
    }

    // ═══════════════════════════════════════════════════════════════
    // P1-2 §10.4 更新凭证
    // PUT /openapi/v1/voucher
    // ═══════════════════════════════════════════════════════════════

    @PutMapping
    @Transactional
    public JsonResult update(@RequestBody VoucherCreateReq req) {
        if (req.getAccountSetsId() == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (req.getId() == null) {
            return JsonResult.failure("id 不能为空（更新凭证必须传 id）");
        }
        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(req.getAccountSetsId()))
                .fetchFirst();
        if (accountSets == null) {
            return JsonResult.failure("账套不存在：" + req.getAccountSetsId());
        }

        // 前置检查：locked / 已审核（VoucherService.update 内部也会再校验，但这里提早返回更好的错误信息）
        Voucher existing = bqf.selectFrom(qVoucher)
                .where(qVoucher.accountSetsId.eq(req.getAccountSetsId()).and(qVoucher.id.eq(req.getId())))
                .fetchFirst();
        if (existing == null) {
            return failureWithType("凭证不存在：" + req.getId(), "NOT_FOUND");
        }
        if (Boolean.TRUE.equals(existing.getLocked())) {
            return failureWithType("凭证已锁定，不允许更新", "ALREADY_LOCKED");
        }
        if (Boolean.TRUE.equals(accountSets.getVoucherReviewed()) && existing.getAuditMemberId() != null) {
            return failureWithType("凭证已审核，不允许更新", "ALREADY_AUDITED");
        }

        try {
            // 构造 VoucherDto 调用现有 update 逻辑（与 saveSingle 共享分录映射）
            VoucherDto dto = buildDto(req, accountSets);
            dto.setId(req.getId());
            voucherService.update(dto, accountSets);

            // sourceInvoiceIds 回写
            List<Long> linkInvoiceIds = req.getSourceInvoiceIds() != null && !req.getSourceInvoiceIds().isEmpty()
                    ? req.getSourceInvoiceIds()
                    : req.getInvoiceIds();
            if (linkInvoiceIds != null && !linkInvoiceIds.isEmpty()) {
                jqf.update(qInvoice)
                        .set(qInvoice.voucherId, req.getId())
                        .where(qInvoice.accountSetsId.eq(accountSets.getId())
                                .and(qInvoice.id.in(linkInvoiceIds)))
                        .execute();
            }

            // FN-NEW-SOURCE-IDS-FULL: 同步 source*Ids（含 bank / salary / 资产三表）。
            // update 路径之前漏了 bsd 回写，借此补齐——BT 编辑凭证不会因为漏写而丢绑定。
            if (req.getSourceBankStatementIds() != null && !req.getSourceBankStatementIds().isEmpty()) {
                jqf.update(qBsd)
                        .set(qBsd.voucherId, req.getId())
                        .where(qBsd.accountSetId.eq(accountSets.getId())
                                .and(qBsd.id.in(req.getSourceBankStatementIds())))
                        .execute();
            }
            writeBackExtendedSources(req, accountSets, req.getId());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", req.getId());
            data.put("voucherDate", req.getVoucherDate());
            return JsonResult.successful(data);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // P0-1 §10.5 凭证删除（仅删未审核 + locked=false）
    // POST /openapi/v1/voucher/delete
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/delete")
    @Transactional
    public JsonResult delete(@RequestBody VoucherDeleteReq req) {
        if (req.getAccountSetsId() == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (req.getVoucherIds() == null || req.getVoucherIds().isEmpty()) {
            return JsonResult.failure("voucherIds 不能为空");
        }
        if (req.getVoucherIds().size() > 100) {
            return JsonResult.failure("一次最多删除 100 条");
        }

        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(req.getAccountSetsId()))
                .fetchFirst();
        if (accountSets == null) {
            return JsonResult.failure("账套不存在：" + req.getAccountSetsId());
        }

        // 先批量查现状以分类
        List<Voucher> existing = bqf.selectFrom(qVoucher)
                .where(qVoucher.accountSetsId.eq(req.getAccountSetsId())
                        .and(qVoucher.id.in(req.getVoucherIds())))
                .fetch();
        Map<Long, Voucher> existingMap = new HashMap<>();
        for (Voucher v : existing) existingMap.put(v.getId(), v);

        List<Long> deletable = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        boolean reviewed = Boolean.TRUE.equals(accountSets.getVoucherReviewed());

        for (Long vid : req.getVoucherIds()) {
            Voucher v = existingMap.get(vid);
            if (v == null) {
                errors.add(errorEntry(vid, "NOT_FOUND", "凭证不存在或不属于该账套"));
                continue;
            }
            if (Boolean.TRUE.equals(v.getLocked())) {
                errors.add(errorEntry(vid, "ALREADY_LOCKED", "凭证已锁定（期间已结账或已审核），不允许删除"));
                continue;
            }
            if (reviewed && v.getAuditMemberId() != null) {
                errors.add(errorEntry(vid, "ALREADY_AUDITED", "账套开启审核制，已审核凭证不允许删除"));
                continue;
            }
            deletable.add(vid);
        }

        int deletedCount = 0;
        if (!deletable.isEmpty()) {
            try {
                voucherService.batchDelete(accountSets, deletable);
                deletedCount = deletable.size();
                // P2-3 配套：清理已关联但凭证已删的发票 voucher_id（避免悬挂引用）
                jqf.update(qInvoice)
                        .setNull(qInvoice.voucherId)
                        .where(qInvoice.accountSetsId.eq(req.getAccountSetsId())
                                .and(qInvoice.voucherId.in(deletable)))
                        .execute();
                // 同步清理银行流水 voucher_id 悬挂引用
                jqf.update(qBsd)
                        .setNull(qBsd.voucherId)
                        .where(qBsd.accountSetId.eq(req.getAccountSetsId())
                                .and(qBsd.voucherId.in(deletable)))
                        .execute();
            } catch (Exception e) {
                log.error("openapi 凭证删除失败", e);
                return failureWithType("删除失败：" + e.getMessage(), "INTERNAL_ERROR");
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalRequested", req.getVoucherIds().size());
        data.put("deleted", deletedCount);
        data.put("skipped", req.getVoucherIds().size() - deletedCount);
        data.put("errors", errors);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // P2-2 §10.6 单张凭证全字段
    // GET /openapi/v1/voucher/{id}
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/{id}")
    public JsonResult getOne(@PathVariable Long id, @RequestParam Long accountSetsId) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        VoucherDto dto = voucherService.getOne(accountSetsId, id);
        if (dto == null) {
            return failureWithType("凭证不存在：" + id, "NOT_FOUND");
        }

        Map<String, Object> m = toBriefDto(dto);
        // 加上分录明细
        List<Map<String, Object>> details = new ArrayList<>();
        if (dto.getDetails() != null) {
            for (VoucherDetailsDto d : dto.getDetails()) {
                Map<String, Object> dd = new LinkedHashMap<>();
                dd.put("id", d.getId());
                dd.put("subjectId", d.getSubjectId());
                dd.put("subjectCode", d.getSubjectCode());
                dd.put("subjectName", d.getSubjectName());
                dd.put("summary", d.getSummary());
                dd.put("debitAmount", d.getDebitAmount());
                dd.put("creditAmount", d.getCreditAmount());
                dd.put("auxiliaryDetailId", d.getAuxiliaryDetailId());
                dd.put("currencyId", d.getCurrencyId());
                dd.put("num", d.getNum());
                dd.put("price", d.getPrice());
                dd.put("cashFlow", Boolean.TRUE.equals(d.getCashFlow()));
                details.add(dd);
            }
        }
        m.put("details", details);
        return JsonResult.successful(m);
    }

    // ═══════════════════════════════════════════════════════════════
    // §3.5 批量创建凭证
    // ═══════════════════════════════════════════════════════════════

    /**
     * POST /openapi/v1/voucher/batch
     */
    @PostMapping("/batch")
    public JsonResult batch(@RequestBody VoucherBatchReq req) {
        if (req.getAccountSetsId() == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (req.getVouchers() == null || req.getVouchers().isEmpty()) {
            return JsonResult.failure("vouchers 不能为空");
        }

        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(req.getAccountSetsId()))
                .fetchFirst();
        if (accountSets == null) {
            return JsonResult.failure("账套不存在：" + req.getAccountSetsId());
        }

        boolean stopOnError = Boolean.TRUE.equals(req.getStopOnError());
        if (stopOnError) {
            return batchAtomic(req, accountSets);
        }
        return batchPartial(req, accountSets);
    }

    @Transactional
    public JsonResult batchAtomic(VoucherBatchReq req, AccountSets accountSets) {
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            for (int i = 0; i < req.getVouchers().size(); i++) {
                VoucherCreateReq item = req.getVouchers().get(i);
                if (item.getAccountSetsId() == null) {
                    item.setAccountSetsId(req.getAccountSetsId());
                }
                VoucherDto saved = saveSingle(item, accountSets);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("index", i);
                r.put("status", "ok");
                r.put("voucherId", saved.getId());
                results.add(r);
            }
        } catch (Exception e) {
            // stopOnError=true：直接整批回滚
            org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus().setRollbackOnly();
            return errorResult(e);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalSubmitted", req.getVouchers().size());
        data.put("succeeded", results.size());
        data.put("failed", 0);
        data.put("results", results);
        return JsonResult.successful(data);
    }

    public JsonResult batchPartial(VoucherBatchReq req, AccountSets accountSets) {
        List<Map<String, Object>> results = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;
        OpenApiVoucherController self = cn.hutool.extra.spring.SpringUtil.getBean(OpenApiVoucherController.class);
        for (int i = 0; i < req.getVouchers().size(); i++) {
            VoucherCreateReq item = req.getVouchers().get(i);
            if (item.getAccountSetsId() == null) {
                item.setAccountSetsId(req.getAccountSetsId());
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("index", i);
            try {
                VoucherDto saved = self.saveSingleInNewTransaction(item, accountSets);
                r.put("status", "ok");
                r.put("voucherId", saved.getId());
                succeeded++;
            } catch (Exception e) {
                r.put("status", "error");
                ErrorInfo info = classifyError(e);
                r.put("errorType", info.type);
                r.put("message", info.message);
                failed++;
            }
            results.add(r);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalSubmitted", req.getVouchers().size());
        data.put("succeeded", succeeded);
        data.put("failed", failed);
        data.put("results", results);
        return JsonResult.successful(data);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public VoucherDto saveSingleInNewTransaction(VoucherCreateReq req, AccountSets accountSets) {
        return saveSingle(req, accountSets);
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════════

    private VoucherDto saveSingle(VoucherCreateReq req, AccountSets accountSets) {
        VoucherDto dto = buildDto(req, accountSets);
        // create 模式下 code 由服务端自动分配（当月该 word 下一个可用号）。
        // 不能为 null，否则 VoucherService.checkCode 里 qVoucher.code.eq(null) 会抛
        // "eq(null) is not allowed" —— 见 BUG B-3
        dto.setCode(voucherService.loadCode(accountSets.getId(), req.getWord(), req.getVoucherDate()));

        // FN-FIX-OPENAPI-NO-AUDIT：撤销 auto-approve；与 web VoucherController.save 对齐。
        // 制单人/最后修改人 优先用 Sa-Token 当前登录用户（BT 桌面端走 fv3token；XW 走自家 token），
        // 没拿到则回退到账套创建人，确保字段非空。
        // ❌ 不再 setAuditMemberId / setAuditDate / setLocked(true) — 让凭证默认就是"未审"，
        // 可由前端正常删除/编辑；reviewStatus 字段保留但只是参数占位，本路径不再消费。
        Long currentUserId = resolveCurrentUserId(accountSets);
        dto.setCreateMember(currentUserId);
        dto.setLastUpdateMember(currentUserId);
        dto.setLastUpdateDate(java.time.LocalDateTime.now());

        VoucherDto saved = voucherService.save(dto, accountSets, true);

        // P2-3：把传入的 sourceInvoiceIds（或同义的 invoiceIds）关联到这张凭证
        // 仅回写属于该账套的发票，防止跨账套污染
        List<Long> linkInvoiceIds = req.getSourceInvoiceIds() != null && !req.getSourceInvoiceIds().isEmpty()
                ? req.getSourceInvoiceIds()
                : req.getInvoiceIds();
        if (linkInvoiceIds != null && !linkInvoiceIds.isEmpty()) {
            jqf.update(qInvoice)
                    .set(qInvoice.voucherId, saved.getId())
                    .where(qInvoice.accountSetsId.eq(accountSets.getId())
                            .and(qInvoice.id.in(linkInvoiceIds)))
                    .execute();
        }

        // 回写银行流水 voucher_id（仅限属于该账套的记录，防止跨账套污染）
        if (req.getSourceBankStatementIds() != null && !req.getSourceBankStatementIds().isEmpty()) {
            jqf.update(qBsd)
                    .set(qBsd.voucherId, saved.getId())
                    .where(qBsd.accountSetId.eq(accountSets.getId())
                            .and(qBsd.id.in(req.getSourceBankStatementIds())))
                    .execute();
        }

        // FN-NEW-SOURCE-IDS-FULL: 回写工资单 / 固定资产折旧 / 资产明细 / 资产变动 的 voucher_id
        writeBackExtendedSources(req, accountSets, saved.getId());

        return saved;
    }

    /**
     * 把 VoucherCreateReq 转成 VoucherDto（create 和 update 共用此映射）。
     * 不设置 code（create 时由 saveSingle 补充；update 时由 VoucherService 内部沿用原 code）。
     */
    private VoucherDto buildDto(VoucherCreateReq req, AccountSets accountSets) {
        if (StrUtil.isBlank(req.getWord())) {
            req.setWord("记");
        }
        if (req.getVoucherDate() == null) {
            throw new ServiceException("voucherDate 不能为空");
        }
        if (req.getDetails() == null || req.getDetails().isEmpty()) {
            throw new ServiceException("details 不能为空");
        }

        VoucherDto dto = new VoucherDto();
        dto.setAccountSetsId(accountSets.getId());
        dto.setWord(req.getWord());
        dto.setVoucherDate(req.getVoucherDate());
        dto.setRemark(req.getRemark());
        dto.setSourceRef(req.getSourceRef());
        dto.setCheckTplId(req.getCheckTplId());
        dto.setReceiptNum(req.getReceiptNum());
        // Phase 3 修订版：skill 端可以自标结转凭证（月末结转/年终结转），凭证级和分录级都要标
        boolean carryForward = Boolean.TRUE.equals(req.getCarryForward());
        dto.setCarryForward(carryForward);

        List<VoucherDetailsDto> details = new ArrayList<>();
        for (VoucherDetailReq d : req.getDetails()) {
            if (d.getSubjectId() == null) {
                throw new ServiceException("subjectId 不能为空");
            }
            VoucherDetailsDto dd = new VoucherDetailsDto();
            dd.setAccountSetsId(accountSets.getId());
            dd.setSubjectId(d.getSubjectId());
            dd.setSummary(d.getSummary());
            dd.setDebitAmount(d.getDebitAmount() != null ? d.getDebitAmount() : BigDecimal.ZERO);
            dd.setCreditAmount(d.getCreditAmount() != null ? d.getCreditAmount() : BigDecimal.ZERO);
            dd.setAuxiliaryDetailId(d.getAuxiliaryDetailId() != null ? d.getAuxiliaryDetailId() : 0L);
            // FN-FIX-VOUCHER-CURRENCY-NORMALIZE-ZERO：缺省币种保留 null（别塞 0），交 VoucherService 收口
            // 归一为账套本位币（双保险——即便收口逻辑变动，null 是"未设置"的规范值，避免 currency_id=0 幽灵行）。
            dd.setCurrencyId(d.getCurrencyId());
            dd.setNum(d.getNum());
            dd.setPrice(d.getPrice());
            dd.setCashFlow(Boolean.TRUE.equals(d.getCashFlow()));
            // 行级 carryForward：优先用行自身的标记，否则继承凭证级
            dd.setCarryForward(d.getCarryForward() != null ? Boolean.TRUE.equals(d.getCarryForward()) : carryForward);
            details.add(dd);
        }
        dto.setDetails(details);
        return dto;
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-NEW-SOURCE-IDS-FULL: 工资 / 固定资产三表的 source ID 回写
    //
    // 与 invoice / bank statement 同样"仅限属于该账套，防止跨账套污染"语义。
    // - salary：每张 source salaryId upsert 一条 salary_voucher 关联行
    //   （SalaryVoucher 表 UniqueConstraint=(templateId, accountSetsId, salaryId, period)，
    //    templateId 必填——若该账套没建过工资模板，则用 findOrCreateDefaultSalaryTemplate 兜底）
    // - assets_depreciation / assets_detail / assets_change：直接 UPDATE voucher_id
    //
    // 每个子方法都是 package-private，方便单测 spy / override（bqf 与 jqf 真实 mock 成本高）。
    // ═══════════════════════════════════════════════════════════════

    /** 顶层 orchestrator：把 4 类 source ID 全部回写到正确的目标表 */
    void writeBackExtendedSources(VoucherCreateReq req, AccountSets accountSets, Long voucherId) {
        if (voucherId == null || accountSets == null) return;
        LocalDate voucherDate = req.getVoucherDate();
        BigDecimal totalDebit = sumDebit(req.getDetails());

        writeBackSalary(req.getSourceSalaryIds(), accountSets, voucherId, totalDebit, voucherDate);
        writeBackAssetDepreciation(req.getSourceAssetDepreciationId(), accountSets.getId(), voucherId);
        writeBackAssetDetails(req.getSourceAssetIds(), req.getSourceAssetDepreciationId(),
                accountSets.getId(), voucherId);
        writeBackAssetChanges(req.getSourceAssetChangeIds(), accountSets.getId(), voucherId);
    }

    /** 工资单回写：每个 salaryId upsert 一条 salary_voucher 关联行 */
    void writeBackSalary(List<Long> salaryIds, AccountSets accountSets, Long voucherId,
                         BigDecimal debitAmount, LocalDate voucherDate) {
        if (salaryIds == null || salaryIds.isEmpty()) return;
        if (voucherDate == null) {
            log.warn("[FN-NEW-SOURCE-IDS-FULL] voucherDate 为空，跳过 salary_voucher 回写 (accountSetsId={}, voucherId={})",
                    accountSets.getId(), voucherId);
            return;
        }
        Long templateId = findOrCreateDefaultSalaryTemplate(accountSets.getId());
        if (templateId == null) {
            // 账套连 voucher_word 都没有 — 极少见，但发生时不阻塞 voucher 创建
            log.warn("[FN-NEW-SOURCE-IDS-FULL] 账套 {} 没有 SalaryVoucherTemplate 且无法自动创建（缺少 voucher_word），"
                            + "跳过 salary_voucher 关联回写（salaryIds={}）",
                    accountSets.getId(), salaryIds);
            return;
        }
        // 仅回写属于该账套的 salaryId，防止跨账套污染
        List<Long> validSalaryIds = filterValidSalaryIds(accountSets.getId(), salaryIds);
        for (Long salaryId : validSalaryIds) {
            upsertSalaryVoucher(accountSets.getId(), voucherId, salaryId, templateId, voucherDate, debitAmount);
        }
    }

    /**
     * 取一个可用的 SalaryVoucherTemplate.id 作为 OpenAPI 回写 salary_voucher 关联表时的 templateId。
     * 业务背景：financial 业务层 SalaryVoucherTemplate 由用户在 web 端"工资凭证模板"页手工建，
     * OpenAPI 路径下若该账套没建过模板，则我们自动建一个最小模板兜底
     * （items=空、isAccrual=false、def=false、voucherWord=该账套第一条凭证字）。
     * <p>返回 null 的唯一场景：账套连一条 voucher_word 都没有（建模板必须有凭证字）。
     */
    Long findOrCreateDefaultSalaryTemplate(Long accountSetsId) {
        Long existing = bqf.selectFrom(qSalaryVoucherTemplate)
                .select(qSalaryVoucherTemplate.id)
                .where(qSalaryVoucherTemplate.accountSetsId.eq(accountSetsId))
                .orderBy(qSalaryVoucherTemplate.id.asc())
                .fetchFirst();
        if (existing != null) return existing;

        Long defaultVoucherWord = bqf.selectFrom(qVoucherWord)
                .select(qVoucherWord.id)
                .where(qVoucherWord.accountSetsId.eq(accountSetsId))
                .orderBy(qVoucherWord.id.asc())
                .fetchFirst();
        if (defaultVoucherWord == null) {
            return null;
        }
        SalaryVoucherTemplate t = new SalaryVoucherTemplate();
        t.setAccountSetsId(accountSetsId);
        t.setTitle("OpenAPI 默认工资模板");
        t.setVoucherWord(defaultVoucherWord);
        t.setIsAccrual(false);
        t.setDef(false);
        t.setItems(new ArrayList<>());
        SalaryVoucherTemplate saved = salaryVoucherTemplateRepository.save(t);
        return saved.getId();
    }

    /** 过滤掉不属于该账套的 salaryId，防止跨账套污染 */
    List<Long> filterValidSalaryIds(Long accountSetsId, List<Long> salaryIds) {
        return bqf.selectFrom(qSalary)
                .select(qSalary.id)
                .where(qSalary.accountSetsId.eq(accountSetsId).and(qSalary.id.in(salaryIds)))
                .fetch();
    }

    /**
     * Upsert 一条 salary_voucher：
     * - 先按 (accountSetsId, salaryId, period) 尝试 UPDATE 已有行（兼容 web 端 / 别的模板生成的）→ 改 voucherId + amount + templateId
     * - 若没有行被更新，再 INSERT 新行
     * 这样 OpenAPI 重做账 / web 与 XW 混用 都能保持单期单工资单只一条关联，避免 UniqueConstraint 冲突。
     */
    void upsertSalaryVoucher(Long accountSetsId, Long voucherId, Long salaryId, Long templateId,
                             LocalDate period, BigDecimal amount) {
        long updated = jqf.update(qSalaryVoucher)
                .set(qSalaryVoucher.voucherId, voucherId)
                .set(qSalaryVoucher.amount, amount != null ? amount : BigDecimal.ZERO)
                .set(qSalaryVoucher.templateId, templateId)
                .where(qSalaryVoucher.accountSetsId.eq(accountSetsId)
                        .and(qSalaryVoucher.salaryId.eq(salaryId))
                        .and(qSalaryVoucher.period.eq(period)))
                .execute();
        if (updated == 0) {
            SalaryVoucher sv = new SalaryVoucher();
            sv.setAccountSetsId(accountSetsId);
            sv.setVoucherId(voucherId);
            sv.setSalaryId(salaryId);
            sv.setTemplateId(templateId);
            sv.setPeriod(period);
            sv.setYear(period.getYear());
            sv.setMonth(period.getMonthValue());
            sv.setAmount(amount != null ? amount : BigDecimal.ZERO);
            salaryVoucherRepository.save(sv);
        }
    }

    /** 固定资产折旧批次：单条 id 直接 UPDATE voucher_id */
    void writeBackAssetDepreciation(Long depreciationId, Long accountSetsId, Long voucherId) {
        if (depreciationId == null) return;
        jqf.update(qAssetsDepreciation)
                .set(qAssetsDepreciation.voucherId, voucherId)
                .where(qAssetsDepreciation.accountSetsId.eq(accountSetsId)
                        .and(qAssetsDepreciation.id.eq(depreciationId)))
                .execute();
    }

    /**
     * 资产明细：按 assets_detail.assert_id（资产主表 ID）匹配。
     * 若 depreciationId 也传了，再追加 depreciationId scope，避免误碰其他批次的明细。
     */
    void writeBackAssetDetails(List<Long> assetIds, Long depreciationId,
                               Long accountSetsId, Long voucherId) {
        if (assetIds == null || assetIds.isEmpty()) return;
        BooleanExpression where = qAssetsDetail.accountSetsId.eq(accountSetsId)
                .and(qAssetsDetail.assertId.in(assetIds));
        if (depreciationId != null) {
            where = where.and(qAssetsDetail.depreciationId.eq(depreciationId));
        }
        jqf.update(qAssetsDetail)
                .set(qAssetsDetail.voucherId, voucherId)
                .where(where)
                .execute();
    }

    /** 固定资产变动：按 id 列表 UPDATE voucher_id */
    void writeBackAssetChanges(List<Long> changeIds, Long accountSetsId, Long voucherId) {
        if (changeIds == null || changeIds.isEmpty()) return;
        jqf.update(qAssetsChange)
                .set(qAssetsChange.voucherId, voucherId)
                .where(qAssetsChange.accountSetsId.eq(accountSetsId)
                        .and(qAssetsChange.id.in(changeIds)))
                .execute();
    }

    private static BigDecimal sumDebit(List<VoucherDetailReq> details) {
        BigDecimal total = BigDecimal.ZERO;
        if (details == null) return total;
        for (VoucherDetailReq d : details) {
            if (d != null && d.getDebitAmount() != null) total = total.add(d.getDebitAmount());
        }
        return total;
    }

    private static Map<String, Object> errorEntry(Long voucherId, String type, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("voucherId", voucherId);
        m.put("errorType", type);
        m.put("message", message);
        return m;
    }

    private static JsonResult failureWithType(String msg, String errorType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorType", errorType);
        return JsonResult.failure(msg).setData(data);
    }

    private Map<String, Object> toBriefDto(VoucherDto v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("accountSetsId", v.getAccountSetsId());
        m.put("word", v.getWord());
        m.put("code", v.getCode());
        m.put("voucherDate", v.getVoucherDate());
        // BT 兼容字段：date="yyyy-MM" 格式；amount 为借方合计；reviewStatus 由 auditMemberId 推导
        m.put("date", v.getYear() != null && v.getMonth() != null
                ? v.getYear() + "-" + String.format("%02d", v.getMonth()) : null);
        m.put("summary", v.getRemark());
        m.put("amount", v.getDebitAmount());
        m.put("reviewStatus", v.getAuditMemberId() == null ? "PENDING" : "APPROVED");
        m.put("confidence", null);   // AI 置信度：系统未存储，BT 降级显示
        m.put("sourceRef", v.getSourceRef());   // 原始单据引用：xiaowei 写入 / BT 读取
        m.put("checkTplId", v.getCheckTplId()); // 结转模板 ID：xiaowei 写入，期末结转页据此识别"已生成"
        m.put("year", v.getYear());
        m.put("month", v.getMonth());
        m.put("remark", v.getRemark());
        m.put("debitAmount", v.getDebitAmount());
        m.put("creditAmount", v.getCreditAmount());
        m.put("auditMemberId", v.getAuditMemberId());
        m.put("auditMemberName", renderAuditorName(v.getAuditMemberId(), v.getAuditMemberName()));
        m.put("auditDate", v.getAuditDate());
        m.put("locked", Boolean.TRUE.equals(v.getLocked()));
        m.put("isSysAuto", null);
        return m;
    }

    /**
     * FN-FIX-OPENAPI-NO-AUDIT：解析当前请求的登录用户 ID，作为 createMember/lastUpdateMember。
     * 优先级：Sa-Token 登录态 → 账套创建人 → 0（兜底，几乎到不了这里）。
     * package-private 是为了单测覆写规避 Sa-Token 真实上下文。
     */
    Long resolveCurrentUserId(AccountSets accountSets) {
        try {
            Object loginId = StpUtil.getLoginIdDefaultNull();
            if (loginId != null) {
                if (loginId instanceof Long l) return l;
                if (loginId instanceof Number n) return n.longValue();
                return Long.parseLong(loginId.toString());
            }
        } catch (Exception ignored) {
            // Sa-Token 未配置或解析异常：走兜底
        }
        if (accountSets != null && accountSets.getCreatorId() != null) {
            return accountSets.getCreatorId();
        }
        return 0L;
    }

    /**
     * 当审核员是 OpenAPI 系统用户（id=0，由 PATCH /voucher reviewStatus=APPROVED 不传 operatorId 时
     * 在 {@link #applyReviewStatus} 内填入）时，渲染为"系统/OpenAPI 自动"，
     * 否则返回上游 join 出的真实名字（可能为 null，由调用方降级显示）。
     */
    static String renderAuditorName(Long auditMemberId, String upstreamName) {
        if (auditMemberId == null) return null;
        if (auditMemberId == OPENAPI_SYSTEM_USER_ID) return OPENAPI_SYSTEM_USER_NAME;
        return upstreamName;
    }

    /**
     * 把异常分类到 errorType。
     */
    private ErrorInfo classifyError(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        String type = "INTERNAL_ERROR";
        if (msg.contains("借贷") && (msg.contains("不相等") || msg.contains("不平"))) {
            type = "BALANCE_MISMATCH";
        } else if (msg.contains("不是末级科目") || msg.contains("末级")) {
            type = "NON_LEAF_SUBJECT";
        } else if (msg.contains("科目信息错误") || msg.contains("科目不存在")) {
            type = "SUBJECT_NOT_FOUND";
        } else if (msg.contains("已结账") || msg.contains("不允许修改")) {
            type = "PERIOD_CLOSED";
        } else if (msg.contains("辅助项")) {
            type = "AUXILIARY_INCOMPLETE";
        } else if (msg.contains("数量")) {
            type = "QUANTITY_REQUIRED";
        }
        return new ErrorInfo(type, msg);
    }

    private JsonResult errorResult(Throwable e) {
        log.warn("openapi 凭证操作失败: {}", e.getMessage());
        ErrorInfo info = classifyError(e);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorType", info.type);
        return JsonResult.failure(info.message).setData(data);
    }

    private static class ErrorInfo {
        final String type;
        final String message;

        ErrorInfo(String type, String message) {
            this.type = type;
            this.message = message;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 请求 DTO
    // ═══════════════════════════════════════════════════════════════

    @Data
    public static class VoucherListReq {
        private Long accountSetsId;
        /** "2025-12" 格式；传入时优先于 year/month，total 也对应过滤后总数 */
        private String period;
        private Integer year;
        private Integer month;
        private String word;
        private String remarkContains;
        private Integer page;
        private Integer size;
        private Boolean includeDetails;     // B-5：true 时每行附带 details 数组（批量 join 查询，无 N+1）
    }

    @Data
    public static class VoucherCreateReq {
        private Long accountSetsId;
        private Long id;                       // P1-2 update 用（create 不需要）
        private String word;
        private LocalDate voucherDate;
        private String remark;
        private Integer receiptNum;
        // Phase 3 修订版：skill 自己生成月末/年终结转凭证时标 true，
        // financial 端的 profit-carry / year-end-carry 仅做"是否归零"校验。
        private Boolean carryForward;
        private List<VoucherDetailReq> details;
        // P2-3：要回写 voucher_id 的发票 ID 列表（与 invoiceIds 字段同义，向后兼容）
        private List<Long> sourceInvoiceIds;
        private List<Long> invoiceIds;
        // 要回写 voucher_id 的银行流水明细 ID 列表
        private List<Long> sourceBankStatementIds;
        // FN-NEW-SOURCE-IDS-FULL: 工资单 ID 列表（每张工资单都会产生一条 salary_voucher 关联行）
        private List<Long> sourceSalaryIds;
        // FN-NEW-SOURCE-IDS-FULL: 折旧批次 ID（每月折旧凭证唯一对应一批 assets_depreciation 记录）
        private Long sourceAssetDepreciationId;
        // FN-NEW-SOURCE-IDS-FULL: 资产 ID 列表（折旧凭证关联到本期所有计提折旧的资产，按 assets_detail.assert_id 匹配）
        private List<Long> sourceAssetIds;
        // FN-NEW-SOURCE-IDS-FULL: 资产变动 ID 列表（assets_change 记录，资产购入/处置/调拨）
        private List<Long> sourceAssetChangeIds;
        // 原始单据引用（自由文本，xiaowei 自标 / BT 显示）
        // 例：'invoice:12001+12002 / bank:9001' 或 '出纳:JV-2026-05-088'
        private String sourceRef;
        // 结转模板 ID（指向 CheckoutTemplate.id）。设置后，
        // financial 期末结转页能识别该凭证为"已生成"，模板下显示"已生成凭证 [查看]"
        private Long checkTplId;
        // FN-FIX-AUTO-APPROVED: 创建时审核状态，缺省 APPROVED（自动审 + 锁定 + audit_member_id=0）
        // 显式传 "PENDING" 可保留为草稿（与老语义一致）
        private String reviewStatus;
    }

    @Data
    public static class VoucherDetailReq {
        private Long subjectId;
        private String summary;
        private BigDecimal debitAmount;
        private BigDecimal creditAmount;
        private Long auxiliaryDetailId;
        private Long currencyId;
        private BigDecimal num;
        private BigDecimal price;
        private Boolean cashFlow;
        // 与凭证级 carryForward 配对：每行也标记
        private Boolean carryForward;
    }

    @Data
    public static class VoucherBatchReq {
        private Long accountSetsId;
        private List<VoucherCreateReq> vouchers;
        private Boolean stopOnError;     // false=单条失败继续，true=失败即整批回滚
    }

    @Data
    public static class VoucherDeleteReq {
        private Long accountSetsId;
        private List<Long> voucherIds;
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-FIX-VOUCHER-DRAFT：reviewStatus 单张 / 批量 PATCH
    //
    // Voucher entity 无显式 reviewStatus 列，FN 端按 auditMemberId 语义映射：
    //   PENDING  = auditMemberId IS NULL（草稿，可改 / 可删）
    //   APPROVED = auditMemberId IS NOT NULL（已审，锁定）
    // ═══════════════════════════════════════════════════════════════

    static final String REVIEW_PENDING  = "PENDING";
    static final String REVIEW_APPROVED = "APPROVED";

    /** FN-FIX-AUTO-APPROVED：OpenAPI 自动审核员的 user.id（migration V20260518_001 插入） */
    static final long OPENAPI_SYSTEM_USER_ID = 0L;
    /** 当 audit_member_id == OPENAPI_SYSTEM_USER_ID 时在响应里显示的审核人名 */
    static final String OPENAPI_SYSTEM_USER_NAME = "系统/OpenAPI 自动";

    /**
     * PATCH /openapi/v1/voucher/{id}
     * body: {accountSetsId, reviewStatus?, remark?, reasonCategory?, reasonText?, aiOriginalData?, operatorId?}
     * <p>
     * 部分更新 voucher（reviewStatus / remark），同时自动写一条 action=EDIT 的 audit log，含 before/after diff。
     * reviewStatus 与 remark 都可独立省略；至少传一个。
     */
    @PatchMapping("/{id}")
    public JsonResult patchOne(@PathVariable("id") Long id, @RequestBody VoucherPatchReq req) {
        if (id == null) return JsonResult.failure("id 不能为空");
        if (req == null) return JsonResult.failure("请求体不能为空");
        if (req.getAccountSetsId() == null) return JsonResult.failure("accountSetsId 不能为空");

        String rs = null;
        if (req.getReviewStatus() != null && !req.getReviewStatus().isBlank()) {
            rs = normalizeReviewStatus(req.getReviewStatus());
            if (rs == null) return JsonResult.failure(
                    "reviewStatus 非法（合法值：PENDING / APPROVED）：" + req.getReviewStatus());
        }
        if (rs == null && req.getRemark() == null) {
            return JsonResult.failure("reviewStatus 与 remark 至少需传一个");
        }
        try {
            // 1. 拍 before snapshot
            Map<String, Object> before = snapshotVoucher(req.getAccountSetsId(), id);
            if (before == null) return JsonResult.failure("凭证不存在：id=" + id);

            // 期间已关账检查（remark-only 路径不经过 applyReviewStatus，需在此显式检查）
            String vdStr = before.get("voucherDate") instanceof String s ? s : null;
            if (vdStr != null && vdStr.length() >= 10) {
                try {
                    LocalDate vd = LocalDate.parse(vdStr);
                    voucherService.checkCheckOut(req.getAccountSetsId(), vd.getYear(), vd.getMonthValue());
                } catch (Exception e) {
                    return JsonResult.failure("期间已关账，禁止修改凭证", 4403);
                }
            }

            // 2. 应用变更
            int affected = 0;
            if (rs != null) {
                affected = applyReviewStatus(req.getAccountSetsId(), List.of(id), rs, req.getOperatorId());
            }
            int remarkAffected = 0;
            if (req.getRemark() != null) {
                remarkAffected = (int) jqf.update(qVoucher)
                        .set(qVoucher.remark, req.getRemark())
                        .where(qVoucher.id.eq(id)
                                .and(qVoucher.accountSetsId.eq(req.getAccountSetsId())))
                        .execute();
            }

            // 3. 拍 after snapshot + 写 audit log
            Map<String, Object> after = snapshotVoucher(req.getAccountSetsId(), id);
            String diffSummary = computeDiffSummary(before, after);
            writeAuditLog(VoucherAuditLog.ACTION_EDIT, id, req.getAccountSetsId(),
                    before, after, diffSummary, req, null);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("reviewStatus", rs);
            data.put("affected", Math.max(affected, remarkAffected));
            data.put("diffSummary", diffSummary);
            return JsonResult.successful(data);
        } catch (ServiceException e) {
            return errorResult(e);
        } catch (Exception e) {
            log.error("[FN-FIX-VOUCHER-DRAFT] patch one failed id={}", id, e);
            return errorResult(e);
        }
    }

    /**
     * POST /openapi/v1/voucher/{id}/reject
     * body: {accountSetsId, reasonCategory?, reasonText?, operatorId?}
     * <p>审核员拒绝 AI 凭证 — 状态回到 PENDING + 写一条 action=REJECT 的 audit log。
     */
    @PostMapping("/{id}/reject")
    public JsonResult reject(@PathVariable("id") Long id, @RequestBody VoucherRejectReq req) {
        if (id == null) return JsonResult.failure("id 不能为空");
        if (req == null || req.getAccountSetsId() == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        try {
            Map<String, Object> before = snapshotVoucher(req.getAccountSetsId(), id);
            if (before == null) return JsonResult.failure("凭证不存在：id=" + id);

            // reject 语义 = 反审（如果当前已审），并打 reject 标
            applyReviewStatus(req.getAccountSetsId(), List.of(id), REVIEW_PENDING, req.getOperatorId());
            Map<String, Object> after = snapshotVoucher(req.getAccountSetsId(), id);
            String diffSummary = computeDiffSummary(before, after);

            VoucherPatchReq carrier = new VoucherPatchReq();
            carrier.setReasonCategory(req.getReasonCategory());
            carrier.setReasonText(req.getReasonText());
            carrier.setOperatorId(req.getOperatorId());
            VoucherAuditLog logRow = writeAuditLog(VoucherAuditLog.ACTION_REJECT, id,
                    req.getAccountSetsId(), before, after, diffSummary, carrier, null);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("auditLogId", logRow.getId());
            data.put("reviewStatus", REVIEW_PENDING);
            data.put("diffSummary", diffSummary);
            return JsonResult.successful(data);
        } catch (ServiceException e) {
            return errorResult(e);
        } catch (Exception e) {
            log.error("[FN-NEW-AUDIT-LOG] reject failed id={}", id, e);
            return errorResult(e);
        }
    }

    /**
     * POST /openapi/v1/voucher/teaching-rule
     * body: {voucherId?, accountSetsId, reasonCategory, teachingRule?, scope?, trigger?, directive?, operatorId?}
     * <p>审核员针对某次拒绝/修改提交教学规则草稿 — 不修改 voucher 本身，仅写一条 action=TEACHING_RULE_DRAFT 的 log。
     * <p>voucherId 可空（针对账套层面的通用规则）。
     * <p>结构化模式：提供 scope/trigger/directive，序列化为 JSON 存入 teachingRule 列。
     */
    @PostMapping("/teaching-rule")
    public JsonResult teachingRule(@RequestBody TeachingRuleReq req) {
        if (req == null || req.getAccountSetsId() == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        // Build effective teachingRule: structured fields override raw string
        String effectiveRule = req.getTeachingRule();
        if (req.getScope() != null || req.getTrigger() != null || req.getDirective() != null) {
            Map<String, String> structured = new LinkedHashMap<>();
            if (req.getScope() != null) structured.put("scope", req.getScope());
            if (req.getTrigger() != null) structured.put("trigger", req.getTrigger());
            if (req.getDirective() != null) structured.put("directive", req.getDirective());
            try {
                effectiveRule = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(structured);
            } catch (Exception ex) {
                effectiveRule = structured.toString();
            }
        }
        if (effectiveRule == null || effectiveRule.isBlank()) {
            return JsonResult.failure("teachingRule 或 scope/trigger/directive 不能都为空");
        }
        try {
            VoucherAuditLog row = new VoucherAuditLog();
            row.setVoucherId(req.getVoucherId() != null ? req.getVoucherId() : 0L);
            row.setAccountSetsId(req.getAccountSetsId());
            row.setAction(VoucherAuditLog.ACTION_TEACHING_RULE_DRAFT);
            row.setReasonCategory(req.getReasonCategory());
            row.setTeachingRule(effectiveRule);
            row.setReasonText(req.getReasonText());
            row.setModifiedBy(req.getOperatorId() != null ? req.getOperatorId() : 0L);
            // period 留空 — 教学规则与具体期间解耦
            VoucherAuditLog saved = auditLogRepository.save(row);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("auditLogId", saved.getId());
            data.put("action", saved.getAction());
            data.put("teachingRule", saved.getTeachingRule());
            return JsonResult.successful(data);
        } catch (Exception e) {
            log.error("[FN-NEW-AUDIT-LOG] teaching-rule failed", e);
            return errorResult(e);
        }
    }

    /**
     * GET /openapi/v1/voucher/audit-log
     * ?voucherId=&accountSetsId=&period=&action=&actions=&months_back=&limit=&page=1&size=20
     * <p>XW 用：按 voucherId / 账套 / 期间 / action(s) 查询 audit log，分页返回。
     * <p>months_back: 省略 period 时往前推 N 月（period >= now-N月）。limit: 覆盖 page/size 取前 N 条。
     * <p>actions: 多值（?actions=REJECT&actions=TEACHING_RULE_DRAFT），与单值 action 合并。
     */
    @GetMapping("/audit-log")
    public JsonResult auditLog(@RequestParam(required = false) Long voucherId,
                               @RequestParam(required = false) Long accountSetsId,
                               @RequestParam(required = false) String period,
                               @RequestParam(required = false) String action,
                               @RequestParam(required = false) List<String> actions,
                               @RequestParam(required = false) Integer monthsBack,
                               @RequestParam(required = false) Integer limit,
                               @RequestParam(required = false, defaultValue = "1") Integer page,
                               @RequestParam(required = false, defaultValue = "20") Integer size) {
        if (limit != null && limit > 0) { size = Math.min(limit, 200); page = 1; }
        if (page == null || page < 1) page = 1;
        if (size == null || size < 1) size = 20;
        if (size > 200) size = 200;

        // normalize actions: merge single action + actions list
        final List<String> normalizedActions = new ArrayList<>();
        if (action != null && !action.isBlank()) normalizedActions.add(action.trim().toUpperCase());
        if (actions != null) actions.stream().filter(a -> a != null && !a.isBlank())
                .map(a -> a.trim().toUpperCase()).forEach(normalizedActions::add);

        // months_back: only applied when period is not specified
        final String minPeriod = (period == null || period.isBlank()) && monthsBack != null && monthsBack > 0
                ? java.time.YearMonth.now().minusMonths(monthsBack).toString()
                : null;

        org.springframework.data.jpa.domain.Specification<VoucherAuditLog> spec =
                (root, q, cb) -> {
                    java.util.List<jakarta.persistence.criteria.Predicate> ps = new java.util.ArrayList<>();
                    if (voucherId != null) ps.add(cb.equal(root.get("voucherId"), voucherId));
                    if (accountSetsId != null) ps.add(cb.equal(root.get("accountSetsId"), accountSetsId));
                    if (period != null && !period.isBlank()) ps.add(cb.equal(root.get("period"), period.trim()));
                    else if (minPeriod != null) ps.add(cb.greaterThanOrEqualTo(root.get("period"), minPeriod));
                    if (!normalizedActions.isEmpty()) ps.add(root.get("action").in(normalizedActions));
                    q.orderBy(cb.desc(root.get("modifiedAt")));
                    return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
                };
        org.springframework.data.domain.Page<VoucherAuditLog> p = auditLogRepository.findAll(spec,
                org.springframework.data.domain.PageRequest.of(page - 1, size));
        List<Map<String, Object>> rows = new ArrayList<>(p.getContent().size());
        for (VoucherAuditLog l : p.getContent()) rows.add(auditLogToDto(l));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", (int) p.getTotalElements());
        data.put("page", page);
        data.put("size", size);
        data.put("records", rows);
        return JsonResult.successful(data);
    }

    // ─── helpers ───

    /** 返回 voucher 关键字段 snapshot；不存在返 null */
    Map<String, Object> snapshotVoucher(Long accountSetsId, Long id) {
        Voucher v = bqf.selectFrom(qVoucher)
                .where(qVoucher.id.eq(id).and(qVoucher.accountSetsId.eq(accountSetsId)))
                .fetchFirst();
        if (v == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("word", v.getWord());
        m.put("code", v.getCode());
        m.put("voucherDate", v.getVoucherDate() == null ? null : v.getVoucherDate().toString());
        m.put("remark", v.getRemark());
        m.put("debitAmount", v.getDebitAmount());
        m.put("creditAmount", v.getCreditAmount());
        m.put("auditMemberId", v.getAuditMemberId());
        m.put("locked", v.getLocked());
        m.put("reviewStatus", v.getAuditMemberId() == null ? REVIEW_PENDING : REVIEW_APPROVED);
        return m;
    }

    /** 比较 before / after 关键字段，生成 "k: a→b" 人类摘要 */
    static String computeDiffSummary(Map<String, Object> before, Map<String, Object> after) {
        if (before == null || after == null) return "";
        List<String> parts = new ArrayList<>();
        for (String k : List.of("remark", "reviewStatus", "auditMemberId", "locked",
                "debitAmount", "creditAmount", "voucherDate")) {
            Object a = before.get(k);
            Object b = after.get(k);
            if (!java.util.Objects.equals(a, b)) {
                parts.add(k + ": " + a + "→" + b);
            }
        }
        return String.join("; ", parts);
    }

    /** 把 snapshot map 序列化成 JSON string；失败回退 toString */
    static String toJsonOrToString(Map<String, Object> m) {
        if (m == null) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
        } catch (Exception e) {
            return String.valueOf(m);
        }
    }

    /** 写一条 audit log，返回入库后的 entity（含 id） */
    VoucherAuditLog writeAuditLog(String action, Long voucherId, Long accountSetsId,
                                  Map<String, Object> before, Map<String, Object> after,
                                  String diffSummary, VoucherPatchReq carrier, String aiOriginalData) {
        VoucherAuditLog row = new VoucherAuditLog();
        row.setVoucherId(voucherId);
        row.setAccountSetsId(accountSetsId);
        row.setAction(action);
        row.setBeforeSnapshot(toJsonOrToString(before));
        row.setAfterSnapshot(toJsonOrToString(after));
        row.setDiffSummary(diffSummary);
        if (carrier != null) {
            row.setReasonCategory(carrier.getReasonCategory());
            row.setReasonText(carrier.getReasonText());
            row.setAiOriginalData(carrier.getAiOriginalData());
            row.setModifiedBy(carrier.getOperatorId() != null ? carrier.getOperatorId() : 0L);
        } else {
            row.setModifiedBy(0L);
        }
        if (aiOriginalData != null) row.setAiOriginalData(aiOriginalData);
        // period 从 after.voucherDate 派生（yyyy-MM）
        if (after != null && after.get("voucherDate") instanceof String s && s.length() >= 7) {
            row.setPeriod(s.substring(0, 7));
        } else if (before != null && before.get("voucherDate") instanceof String s && s.length() >= 7) {
            row.setPeriod(s.substring(0, 7));
        }
        return auditLogRepository.save(row);
    }

    static Map<String, Object> auditLogToDto(VoucherAuditLog l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("voucherId", l.getVoucherId());
        m.put("accountSetsId", l.getAccountSetsId());
        m.put("period", l.getPeriod());
        m.put("action", l.getAction());
        m.put("aiOriginalData", l.getAiOriginalData());
        m.put("before", l.getBeforeSnapshot());
        m.put("after", l.getAfterSnapshot());
        m.put("diffSummary", l.getDiffSummary());
        m.put("reasonCategory", l.getReasonCategory());
        m.put("reasonText", l.getReasonText());
        m.put("teachingRule", l.getTeachingRule());
        m.put("modifiedBy", l.getModifiedBy());
        m.put("modifiedAt", l.getModifiedAt());
        return m;
    }

    /**
     * PATCH /openapi/v1/voucher/batch  body: {accountSetsId, ids, reviewStatus, operatorId?}
     * <p>批量改 reviewStatus；不允许跨账套；ids 为空则失败。返回 affected 行数。
     */
    @PatchMapping("/batch")
    public JsonResult patchBatch(@RequestBody VoucherPatchBatchReq req) {
        if (req == null) return JsonResult.failure("请求体不能为空");
        if (req.getAccountSetsId() == null) return JsonResult.failure("accountSetsId 不能为空");
        if (req.getIds() == null || req.getIds().isEmpty()) return JsonResult.failure("ids 不能为空");
        String rs = normalizeReviewStatus(req.getReviewStatus());
        if (rs == null) return JsonResult.failure(
                "reviewStatus 非法（合法值：PENDING / APPROVED）：" + req.getReviewStatus());
        try {
            int affected = applyReviewStatus(req.getAccountSetsId(), req.getIds(), rs, req.getOperatorId());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ids", req.getIds());
            data.put("reviewStatus", rs);
            data.put("affected", affected);
            data.put("skipped", req.getIds().size() - affected);
            return JsonResult.successful(data);
        } catch (ServiceException e) {
            return errorResult(e);
        } catch (Exception e) {
            log.error("[FN-FIX-VOUCHER-DRAFT] patch batch failed ids={}", req.getIds(), e);
            return errorResult(e);
        }
    }

    /**
     * 实际执行 reviewStatus 切换。
     * - APPROVED：锁定 + 设 auditMemberId/auditDate（仅作用于当前 IS NULL 的）
     * - PENDING：反审 — 清 auditMemberId/auditDate + 解锁（仅作用于当前 IS NOT NULL 的）
     * <p>两侧都先按 voucherDate 派生 (year, month) 调 {@link VoucherService} 的结账校验，
     * 已结账期间禁止改动（与现有 audit/cancelAudit 行为一致）。
     *
     * @return 受影响行数
     */
    int applyReviewStatus(Long accountSetsId, List<Long> ids, String reviewStatus, Long operatorId) {
        List<Voucher> existing = bqf.selectFrom(qVoucher)
                .where(qVoucher.accountSetsId.eq(accountSetsId).and(qVoucher.id.in(ids)))
                .fetch();
        if (existing.isEmpty()) return 0;

        // 校验所有涉及 (year, month) 的期间是否结账
        Set<List<Integer>> ymSet = new LinkedHashSet<>();
        for (Voucher v : existing) {
            if (v.getVoucherDate() != null) {
                ymSet.add(List.of(v.getVoucherDate().getYear(), v.getVoucherDate().getMonthValue()));
            }
        }
        for (List<Integer> ym : ymSet) {
            voucherService.checkCheckOut(accountSetsId, ym.get(0), ym.get(1));
        }

        if (REVIEW_APPROVED.equals(reviewStatus)) {
            long opId = operatorId != null ? operatorId : 0L;  // 0 = SYSTEM
            return (int) jqf.update(qVoucher)
                    .set(qVoucher.locked, true)
                    .set(qVoucher.auditMemberId, opId)
                    .set(qVoucher.auditDate, java.time.LocalDateTime.now())
                    .where(qVoucher.accountSetsId.eq(accountSetsId)
                            .and(qVoucher.id.in(ids))
                            .and(qVoucher.auditMemberId.isNull()))
                    .execute();
        }
        // PENDING：反审 — 仅作用于当前已审的；locked 也跟着回退
        return (int) jqf.update(qVoucher)
                .set(qVoucher.locked, false)
                .setNull(qVoucher.auditMemberId)
                .setNull(qVoucher.auditDate)
                .where(qVoucher.accountSetsId.eq(accountSetsId)
                        .and(qVoucher.id.in(ids))
                        .and(qVoucher.auditMemberId.isNotNull()))
                .execute();
    }

    static String normalizeReviewStatus(String s) {
        if (s == null) return null;
        String u = s.trim().toUpperCase();
        if (REVIEW_PENDING.equals(u) || REVIEW_APPROVED.equals(u)) return u;
        return null;
    }

    @Data
    public static class VoucherPatchReq {
        private Long accountSetsId;
        private String reviewStatus;   // PENDING / APPROVED；与 remark 至少传一个
        private String remark;         // 修改 voucher.remark
        private Long operatorId;       // 可选；APPROVED 时记录到 auditMemberId

        // FN-NEW-AUDIT-LOG：写入 audit log 的元信息（不影响 voucher 本体）
        private String reasonCategory; // SUBJECT_WRONG / AMOUNT_WRONG / FORMAT_ISSUE / ...
        private String reasonText;
        private String aiOriginalData; // 首次记录 AI 原始 JSON
    }

    @Data
    public static class VoucherPatchBatchReq {
        private Long accountSetsId;
        private List<Long> ids;
        private String reviewStatus;
        private Long operatorId;
    }

    @Data
    public static class VoucherRejectReq {
        private Long accountSetsId;
        private String reasonCategory;
        private String reasonText;
        private Long operatorId;
    }

    @Data
    public static class TeachingRuleReq {
        private Long voucherId;        // 可空（账套层面通用规则）
        private Long accountSetsId;
        private String reasonCategory;
        private String reasonText;
        private String teachingRule;   // 原始字符串（后向兼容）；scope/trigger/directive 优先
        private String scope;          // 作用范围，例如 "ACCOUNT_SETS" / "VOUCHER_TYPE"
        private String trigger;        // 触发条件，例如 "DEBIT_CREDIT_MISMATCH"
        private String directive;      // 指导规则内容
        private Long operatorId;
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-FIX-VOUCHER-DELETE-CASCADE：一键清空账套指定期间凭证 + 反向引用 + 重置结转状态
    // ═══════════════════════════════════════════════════════════════
    // FN-API-GAPS ③ 做账前置完整性检查 —— GET /openapi/v1/voucher/completeness?accountSetsId=&period=
    //   复用 VoucherCompletenessService（FN-NEW-COMPLETENESS-REPORT），返回各数据源 vs 已生成凭证对账
    //   + 月末结转完成状态。给 BT 申报前置检查接真数（替代 mock 兜底）。
    // ═══════════════════════════════════════════════════════════════
    @GetMapping("/completeness")
    public JsonResult completeness(@RequestParam Long accountSetsId,
                                   @RequestParam String period) {
        if (accountSetsId == null) return JsonResult.failure("accountSetsId 不能为空");
        if (period == null || period.isBlank()) return JsonResult.failure("period 不能为空（yyyy-MM）");
        try {
            return JsonResult.successful(voucherCompletenessService.report(accountSetsId, period.trim()));
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("[FN-API-GAPS] voucher/completeness failed acc={} period={}", accountSetsId, period, e);
            return JsonResult.failure("完整性检查失败：" + e.getMessage());
        }
    }

    // POST /openapi/v1/voucher/clear-period
    // body: { accountSetsId, year, month }
    //
    // 替代「手工 SQL 删 voucher 表」的清账重跑场景。手工 SQL 不会触发 VoucherProcessor
    // 链 → salary_voucher / cashier_journal.voucher_id / invoice.voucher_id 全部残留
    // → 下次做账时各幂等检查被骗、跳过生成。
    //
    // 走 voucherService.batchDelete 即触发完整 9 个 VoucherProcessor + Checkout.status
    // 联动 + 余额重算。
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/clear-period")
    @Transactional
    public JsonResult clearPeriod(@RequestBody ClearPeriodReq req) {
        if (req == null || req.getAccountSetsId() == null
                || req.getYear() == null || req.getMonth() == null) {
            return JsonResult.failure("accountSetsId/year/month 不能为空");
        }
        if (req.getMonth() < 1 || req.getMonth() > 12) {
            return JsonResult.failure("month 取值范围 1-12：" + req.getMonth());
        }

        AccountSets accountSets = findAccountSetForTest(req.getAccountSetsId());
        if (accountSets == null) {
            return JsonResult.failure("账套不存在：" + req.getAccountSetsId());
        }

        // 期初状态 — 供返回展示
        Integer statusBefore = loadCheckoutStatusForTest(
                req.getAccountSetsId(), req.getYear(), req.getMonth());
        if (Integer.valueOf(2).equals(statusBefore)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("errorType", "PERIOD_CLOSED");
            return JsonResult.failure("期间已结账（status=2），不允许清空凭证；请先反结账").setData(data);
        }

        // 查本期 voucher id（不带 locked/审核闸门 — batchDelete 内部会再过滤）
        List<Long> voucherIds = loadPeriodVoucherIdsForTest(
                req.getAccountSetsId(), req.getYear(), req.getMonth());

        int deleted = 0;
        if (!voucherIds.isEmpty()) {
            // batchDelete 触发 9 个 VoucherProcessor + Checkout.status 联动（已结转→未结转）
            voucherService.batchDelete(accountSets, voucherIds);
            deleted = voucherIds.size();
        }

        // 同步重算余额，让科目余额表本期发生额归零
        try {
            balanceCalcService.calcSynchronization(req.getAccountSetsId(),
                    java.time.YearMonth.of(req.getYear(), req.getMonth()));
        } catch (Exception e) {
            log.warn("[FN-FIX-VOUCHER-DELETE-CASCADE] clear-period 重算余额失败：{}", e.getMessage());
        }

        // 显式强制 Checkout.status=0（保险，batchDelete 内部只在删 carry_forward 凭证时回退，
        // 这里覆盖整月清空场景，无 carry_forward 也要强制回 0）
        try {
            checkoutService.updateStatus(req.getAccountSetsId(), req.getYear(), req.getMonth(), 0);
        } catch (Exception e) {
            log.warn("[FN-FIX-VOUCHER-DELETE-CASCADE] clear-period 重置 status 失败：{}", e.getMessage());
        }

        Integer statusAfter = loadCheckoutStatusForTest(
                req.getAccountSetsId(), req.getYear(), req.getMonth());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountSetsId", req.getAccountSetsId());
        data.put("year", req.getYear());
        data.put("month", req.getMonth());
        data.put("deleted", deleted);
        data.put("voucherIds", voucherIds);
        data.put("statusBefore", statusBefore != null ? statusBefore : 0);
        data.put("statusAfter", statusAfter != null ? statusAfter : 0);
        return JsonResult.successful(data);
    }

    /** package-private test hook：单测覆写避开 BlazeJPAQuery 直查 */
    List<Long> loadPeriodVoucherIdsForTest(Long accountSetsId, Integer year, Integer month) {
        return bqf.selectFrom(qVoucher)
                .select(qVoucher.id)
                .where(qVoucher.accountSetsId.eq(accountSetsId)
                        .and(qVoucher.year.eq(year))
                        .and(qVoucher.month.eq(month)))
                .fetch();
    }

    /** package-private test hook：拿 Checkout.status（不存在返 null） */
    Integer loadCheckoutStatusForTest(Long accountSetsId, Integer year, Integer month) {
        cn.gson.financial.kernel.model.entity.QCheckout qCheckout =
                cn.gson.financial.kernel.model.entity.QCheckout.checkout;
        return bqf.selectFrom(qCheckout).select(qCheckout.status)
                .where(qCheckout.accountSetsId.eq(accountSetsId)
                        .and(qCheckout.checkYear.eq(year))
                        .and(qCheckout.checkMonth.eq(month)))
                .fetchFirst();
    }

    @Data
    public static class ClearPeriodReq {
        private Long accountSetsId;
        private Integer year;
        private Integer month;
    }
}
