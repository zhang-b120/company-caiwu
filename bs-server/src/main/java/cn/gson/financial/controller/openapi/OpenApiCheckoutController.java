package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.dto.ProfitCheckDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Balance;
import cn.gson.financial.kernel.model.entity.Checkout;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.entity.QBalance;
import cn.gson.financial.kernel.model.entity.QBankStatementDetail;
import cn.gson.financial.kernel.model.entity.QCheckout;
import cn.gson.financial.kernel.model.entity.QFileInfo;
import cn.gson.financial.kernel.model.entity.QInvoice;
import cn.gson.financial.kernel.model.query.InvoicePeriodPredicate;
import cn.gson.financial.kernel.model.entity.QSalary;
import cn.gson.financial.kernel.model.entity.QSalaryVoucher;
import cn.gson.financial.kernel.model.entity.QVoucher;
import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.dto.ReportItemsDto;
import cn.gson.financial.kernel.model.dto.TrialBalanceDto;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.service.AccountSetsService;
import cn.gson.financial.kernel.service.AssetsService;
import cn.gson.financial.kernel.service.BalanceCalcService;
import cn.gson.financial.kernel.service.BalanceService;
import cn.gson.financial.kernel.service.CashierJournalService;
import cn.gson.financial.kernel.service.CashierReportService;
import cn.gson.financial.kernel.service.CheckoutService;
import cn.gson.financial.kernel.service.CheckoutTemplateService;
import cn.gson.financial.kernel.service.CurrencyService;
import cn.gson.financial.kernel.service.InvoiceService;
import cn.gson.financial.kernel.service.ReportService;
import cn.gson.financial.kernel.service.SalaryService;
import cn.gson.financial.kernel.service.SubjectService;
import cn.gson.financial.kernel.service.VoucherService;
import cn.gson.financial.kernel.model.entity.Voucher;
import cn.gson.financial.kernel.model.vo.CashierAccountCheckVo;
import cn.gson.financial.task.workbench.service.TaskCarryForwardService;
import cn.hutool.core.lang.Dict;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * 开放 API — 结账闭环
 * 路径前缀 /openapi/v1/checkout
 *
 *  GET  /                    §9   期间结账状态
 *  POST /profit-carry        P3-1 月末结转损益（仅校验 + 推状态，凭证由 XW 端推）
 *  POST /profit-carry-auto   P3-1b 月末结转损益（financial 端自动生成凭证 + 推状态）
 *  POST /year-end-carry      P3-2 年终结转利润（12 月专用）
 *  POST /                    P3-3 实际结账
 *
 * status 推进路径：0（未结转损益）→ 1（已结转损益）→ 2（已结账）
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/checkout")
@SaIgnore
public class OpenApiCheckoutController {

    private final CheckoutService checkoutService;
    private final SubjectService subjectService;
    private final AccountSetsService accountSetsService;
    private final BalanceCalcService balanceCalcService;
    private final CurrencyService currencyService;
    private final CheckoutTemplateService checkoutTemplateService;
    // FN-NEW-OPENAPI-CARRYOVER-CHECKS: 9 项检查所依赖的 7 个 service
    private final BalanceService balanceService;
    private final ReportService reportService;
    private final CashierJournalService cashierJournalService;
    private final CashierReportService cashierReportService;
    private final AssetsService assetsService;
    private final SalaryService salaryService;
    private final InvoiceService invoiceService;
    // FN-NEW-OPENAPI-PROFIT-CARRY-AUTO: 旧版结转损益凭证生成器。FN-FIX-PL-CARRY-VERIFY-ONLY
    // 起 profit-carry-auto 已改 verify-only（xiaowei 建 pl_carry），此处保留 bean 注入以
    // 不破坏构造器签名 + 测试 mock；execute() 不再被本 Controller 调用。
    private final TaskCarryForwardService taskCarryForwardService;
    private final VoucherService voucherService;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    // FN-API-GAPS：close-period / clear-period 委托现有端点（单一真相源，状态机与之一致），@Lazy 防循环初始化
    @Resource
    @Lazy
    private OpenApiClosePeriodController closePeriodController;
    @Resource
    @Lazy
    private OpenApiVoucherController voucherController;

    private static final QCheckout qCheckout = QCheckout.checkout;
    private static final QAccountSets qAccountSets = QAccountSets.accountSets;
    private static final QBalance qBalance = QBalance.balance;
    // FN-NEW-OPENAPI-CARRYOVER-CHECKS: 用于失败项明细查询
    private static final QInvoice qInvoice = QInvoice.invoice;
    private static final QSalary qSalary = QSalary.salary;
    private static final QSalaryVoucher qSalaryVoucher = QSalaryVoucher.salaryVoucher;
    private static final QVoucher qVoucher = QVoucher.voucher;
    private static final QBankStatementDetail qBsd = QBankStatementDetail.bankStatementDetail;
    private static final QFileInfo qFileInfo = QFileInfo.fileInfo;

    private static final BigDecimal CENT = new BigDecimal("0.01");

    // ═══════════════════════════════════════════════════════════════
    // §9 期间结账状态查询
    // GET /openapi/v1/checkout?accountSetsId=88&year=2026&month=5
    // ═══════════════════════════════════════════════════════════════

    @GetMapping
    public JsonResult status(@RequestParam Long accountSetsId,
                             @RequestParam Integer year,
                             @RequestParam Integer month) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (year == null || month == null) {
            return JsonResult.failure("year/month 不能为空");
        }

        Checkout c = findCheckout(accountSetsId, year, month);

        Map<String, Object> m = new LinkedHashMap<>();
        if (c == null) {
            m.put("checkedOut", false);
            m.put("checkoutDate", null);
            m.put("status", 0);
        } else {
            boolean checkedOut = Integer.valueOf(2).equals(c.getStatus());
            m.put("checkedOut", checkedOut);
            m.put("checkoutDate", c.getCheckDate());
            m.put("status", c.getStatus());
        }
        m.put("year", year);
        m.put("month", month);
        return JsonResult.successful(m);
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-API-GAPS ① 关账（推进账期）—— POST /openapi/v1/checkout/close-period
    //   body: { accountSetsId, year, month, force? }
    //   委托 OpenApiClosePeriodController（单一真相源）：校验无 PENDING + 借贷平衡 + 未重复关账，
    //   Checkout.status→2 + currentAccountDate 推进 + 锁凭证 + step_carry=done。
    //   与 /profit-carry-auto 状态机一致（0→1 结转损益→2 关账，本接口走 1/0→2 的关账段）。
    // ═══════════════════════════════════════════════════════════════
    @PostMapping("/close-period")
    public JsonResult closePeriod(@RequestBody CheckoutReq req) {
        if (req == null || req.getAccountSetsId() == null) return JsonResult.failure("accountSetsId 不能为空");
        if (req.getYear() == null || req.getMonth() == null) return JsonResult.failure("year/month 不能为空");
        OpenApiClosePeriodController.ClosePeriodReq cpr = new OpenApiClosePeriodController.ClosePeriodReq();
        cpr.setPeriod(String.format("%04d-%02d", req.getYear(), req.getMonth()));
        cpr.setForce(Boolean.TRUE.equals(req.getForceMode()));
        return closePeriodController.closePeriod(req.getAccountSetsId(), cpr);
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-API-GAPS ② 重做/清账期 —— POST /openapi/v1/checkout/clear-period
    //   body: { accountSetsId, year, month }
    //   委托 OpenApiVoucherController.clearPeriod（单一真相源）：batchDelete 触发 9 个 VoucherProcessor
    //   + 余额重算 + Checkout.status→0。已结账(status=2)拒（PERIOD_CLOSED，需先反结账 /uncheck-period），
    //   与关账状态机一致（不在已关账态下清空）。
    // ═══════════════════════════════════════════════════════════════
    @PostMapping("/clear-period")
    public JsonResult clearPeriod(@RequestBody CheckoutReq req) {
        if (req == null || req.getAccountSetsId() == null) return JsonResult.failure("accountSetsId 不能为空");
        if (req.getYear() == null || req.getMonth() == null) return JsonResult.failure("year/month 不能为空");
        OpenApiVoucherController.ClearPeriodReq cr = new OpenApiVoucherController.ClearPeriodReq();
        cr.setAccountSetsId(req.getAccountSetsId());
        cr.setYear(req.getYear());
        cr.setMonth(req.getMonth());
        return voucherController.clearPeriod(cr);
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-NEW-OPENAPI-CARRYOVER-CHECKS 一站式 9 项结账前检查
    // GET /openapi/v1/checkout/carryover-checks?accountSetsId=88&year=2025&month=12
    //
    // 等价于 financial UI "结转到下期" 按钮触发的 9 项检查 + 2 项辅助检查（profitCheck/reviewedCheck）。
    // 每项独立 try/catch，单项失败不阻塞其他项。整体响应永远 200（除非鉴权/参数错）。
    // overallPassed = 9 项基础检查全 passed（不含 profitCheck/reviewedCheck）
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/carryover-checks")
    public JsonResult carryoverChecks(@RequestParam Long accountSetsId,
                                      @RequestParam Integer year,
                                      @RequestParam Integer month) {
        if (accountSetsId == null) return JsonResult.failure("accountSetsId 不能为空");
        if (year == null || month == null) return JsonResult.failure("year/month 不能为空");

        AccountSets acc = findAccountSetForTest(accountSetsId);
        if (acc == null) return JsonResult.failure("账套不存在：" + accountSetsId);

        Long localCurrencyId;
        try {
            localCurrencyId = currencyService.fetchLocal(accountSetsId).getId();
        } catch (Exception e) {
            log.warn("carryover-checks fetchLocal 失败 acc={}", accountSetsId, e);
            localCurrencyId = null;
        }
        final Long localCurrencyIdF = localCurrencyId;

        Map<String, Object> data = new LinkedHashMap<>();
        List<String> failedItems = new ArrayList<>();

        // 1. 期初检查
        data.put("beginCheck", safeBaseCheck("beginCheck", failedItems, () -> {
            boolean passed = checkoutService.beginCheck(acc);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("passed", passed);
            m.put("detail", null);
            return m;
        }));

        // 2. 期末检查（试算平衡）
        data.put("finalCheck", safeBaseCheck("finalCheck", failedItems, () -> {
            LocalDate date = LocalDate.of(year, month, 1);
            TrialBalanceDto tb = balanceService.trialBalance(accountSetsId, date);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("passed", tb.isFlat());
            m.put("trialBalance", tb);
            return m;
        }));

        // 3. 报表检查（资产 = 负债 + 权益）
        data.put("reportCheck", safeBaseCheck("reportCheck", failedItems, () -> {
            Map<String, Object> m = new LinkedHashMap<>();
            if (localCurrencyIdF == null) {
                m.put("passed", false);
                m.put("error", "本位币未配置");
                return m;
            }
            List<ReportItemsDto> items = reportService.items("zcfz", accountSetsId, false);
            List<ReportItemsDto> zcfz = reportService.reportData(
                    items, acc, "zcfz", localCurrencyIdF, LocalDate.of(year, month, 1), "TERM");
            ReportItemsDto asset = zcfz.stream().filter(it -> "资产总计".equals(it.getTitle()))
                    .findFirst().orElse(null);
            ReportItemsDto liability = zcfz.stream().filter(it -> "负债和所有者权益（或股东权益）总计".equals(it.getTitle())
                    || "负债和净资产总计".equals(it.getTitle())).findFirst().orElse(null);
            ReportItemsDto ownerEq = zcfz.stream().filter(it -> "所有者权益（或股东权益）合计".equals(it.getTitle())
                    || "净资产合计".equals(it.getTitle())).findFirst().orElse(null);
            ReportItemsDto totalLia = zcfz.stream().filter(it -> "负债合计".equals(it.getTitle()))
                    .findFirst().orElse(null);
            if (asset == null || liability == null || ownerEq == null || totalLia == null) {
                m.put("passed", false);
                m.put("error", "资产负债表关键行缺失");
                return m;
            }
            BigDecimal a = round2(asset.getValues().get("期末余额"));
            BigDecimal tl = round2(totalLia.getValues().get("期末余额"));
            BigDecimal oe = round2(ownerEq.getValues().get("期末余额"));
            BigDecimal libVal = round2(liability.getValues().get("期末余额"));
            boolean endEq = a.compareTo(libVal) == 0;
            BigDecimal rights = a.subtract(tl);
            m.put("passed", endEq && rights.compareTo(oe) == 0);
            m.put("asset", a);
            m.put("liability", tl);
            m.put("ownerEquity", oe);
            m.put("diff", rights.subtract(oe));
            m.put("endEq", endEq);
            return m;
        }));

        // 4. 断号检查
        data.put("brokenCheck", safeBaseCheck("brokenCheck", failedItems, () -> {
            boolean passed = checkoutService.brokenCheck(accountSetsId, year, month);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("passed", passed);
            m.put("missing", Collections.emptyList());   // V1: 仅返 boolean，明细在 UI 上由 /broken/list 单独查
            return m;
        }));

        // 5. 资金检查
        data.put("cashierCheck", safeBaseCheck("cashierCheck", failedItems, () -> {
            LocalDate start = LocalDate.of(year, month, 1);
            LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
            // FN-FIX-CASHIER-CHECK-LOGIC：openapi + web 共用 cashierReportService.cashierPassed 单一真相源
            // （= 全部日记账已做凭证 且 期末账实相符）。原 `cashiered && hasDiff` 极性反、且只在顶层找「差异」行
            // （实际嵌在 children）→ 账实相符/0 业务月恒不过，已修。
            boolean passed = cashierReportService.cashierPassed(accountSetsId, start, end, localCurrencyIdF);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("passed", passed);
            m.put("diff", null);   // 差异明细由出纳-账户对账报表单独查（原 hasDiff 为反极性 bug 值，已移除）
            return m;
        }));

        // 6. 资产检查
        data.put("assetsCheck", safeBaseCheck("assetsCheck", failedItems, () -> {
            boolean passed = assetsService.assetsCheck(accountSetsId, year, month);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("passed", passed);
            m.put("unDepreciatedCount", 0);   // V1: detail 由 /assets 模块单独查
            return m;
        }));

        // 7. 工资检查
        data.put("salaryCheck", safeBaseCheck("salaryCheck", failedItems, () -> {
            boolean passed = salaryService.salaryCheck(accountSetsId, year, month);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("passed", passed);
            if (!passed) {
                m.put("message", "工资模块有工资记录未生成凭证");
                // 明细查询独立 try/catch — 即便查询失败，message + 空数组仍保留，response 结构稳定
                List<Long> unBound = fetchUnBoundSalaryIdsForTest(accountSetsId, year, month);
                m.put("unBoundSalaryIds", unBound);
                m.put("count", unBound.size());
            } else {
                m.put("unBoundSalaryIds", Collections.emptyList());
                m.put("count", 0);
            }
            return m;
        }));

        // 8. 发票检查
        data.put("invoiceCheck", safeBaseCheck("invoiceCheck", failedItems, () -> {
            boolean passed = invoiceService.invoiceCheck(accountSetsId, year, month);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("passed", passed);
            if (!passed) {
                m.put("message", "发票模块有发票记录未生成凭证");
                List<Long> unBound = fetchUnBoundInvoiceIdsForTest(accountSetsId, year, month);
                m.put("unBoundInvoiceIds", unBound);
                m.put("count", unBound.size());
            } else {
                m.put("unBoundInvoiceIds", Collections.emptyList());
                m.put("count", 0);
            }
            return m;
        }));

        // 9. 银行流水检查（信息提示项 — 仅判存在与否）
        data.put("bankStatementCheck", safeBaseCheck("bankStatementCheck", failedItems, () -> {
            int[] counts = fetchBankStatementCountsForTest(accountSetsId, year, month);
            Map<String, Object> m = new LinkedHashMap<>();
            // 信息提示项：保持 passed=true；BT 自行决定如何呈现 hasBankStatement / unBoundCount
            m.put("passed", true);
            m.put("hasBankStatement", counts[0] > 0);
            m.put("unBoundCount", counts[1]);
            return m;
        }));

        // ─── 辅助项（不计入 overallPassed）───
        // FN-FIX-PRECLOSE-CHECK-VS-CLOSE-CONSISTENCY：profitCheck / reviewedCheck 为「建议项」，
        // 不计入 overallPassed，也不 gate close-period —— 与关账端口径一致（close-period 已于
        // FN-FIX-OPENAPI-NO-AUDIT 2026-05-18 撤销 PENDING 拦截，业务无人工审核环节，PENDING 不阻断关账）。
        // 每项显式带 blocking=false，调用方（BT verify_pre_close / XW month_end）务必只按 overallPassed/
        // failedItems 放行，禁止单独 gate 这两项的 passed=false，否则与关账端结论相反（检查说不能关、关账说能关）。

        // 损益检查（profitCheck）—— 建议项，不阻断关账
        data.put("profitCheck", safeAuxCheck("profitCheck", () -> {
            List<ProfitCheckDto> remaining = checkoutService.profitCheck(accountSetsId, year, month);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("passed", remaining == null || remaining.isEmpty());
            m.put("blocking", false);
            m.put("remaining", remaining != null ? remaining : Collections.emptyList());
            return m;
        }));

        // 审核检查（reviewedCheck）—— 建议项，不阻断关账（无人工审核环节，autogen/XW 推送均默认 APPROVED）
        data.put("reviewedCheck", safeAuxCheck("reviewedCheck", () -> {
            boolean passed = checkoutService.reviewedCheck(accountSetsId, year, month);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("passed", passed);
            m.put("blocking", false);
            m.put("pendingVoucherIds", passed
                    ? Collections.emptyList()
                    : fetchPendingVoucherIdsForTest(accountSetsId, year, month));
            return m;
        }));

        // 汇总
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountSetsId", accountSetsId);
        result.put("year", year);
        result.put("month", month);
        result.put("overallPassed", failedItems.isEmpty());
        result.put("failedItems", failedItems);
        // FN-FIX-PRECLOSE-CHECK-VS-CLOSE-CONSISTENCY：建议项清单——这些项 passed=false 不阻断关账，
        // 调用方只按 overallPassed/failedItems 决策（与 close-period 端同口径）。
        result.put("advisoryItems", List.of("profitCheck", "reviewedCheck"));
        result.putAll(data);
        return JsonResult.successful(result);
    }

    /** 9 项基础检查的安全执行器：try/catch + 失败入 failedItems */
    Map<String, Object> safeBaseCheck(String key, List<String> failedItems,
                                      java.util.function.Supplier<Map<String, Object>> check) {
        try {
            Map<String, Object> result = check.get();
            if (!Boolean.TRUE.equals(result.get("passed"))) failedItems.add(key);
            return result;
        } catch (Exception e) {
            log.warn("carryover-checks {} 执行失败", key, e);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("passed", false);
            m.put("error", e.getMessage());
            failedItems.add(key);
            return m;
        }
    }

    /** 辅助项检查（profitCheck / reviewedCheck）：try/catch 但不影响 overallPassed */
    Map<String, Object> safeAuxCheck(String key,
                                     java.util.function.Supplier<Map<String, Object>> check) {
        try {
            return check.get();
        } catch (Exception e) {
            log.warn("carryover-checks {} 辅助项执行失败", key, e);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("passed", false);
            m.put("error", e.getMessage());
            return m;
        }
    }

    private static BigDecimal round2(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** package-private hook：未生成凭证的 salary ID 明细。失败时返空数组而非抛异常（保 response 结构稳定） */
    List<Long> fetchUnBoundSalaryIdsForTest(Long accountSetsId, Integer year, Integer month) {
        try {
            return bqf.selectFrom(qSalary).select(qSalary.id)
                    .leftJoin(qSalaryVoucher).on(qSalary.accountSetsId.eq(qSalaryVoucher.accountSetsId)
                            .and(qSalary.id.eq(qSalaryVoucher.salaryId)))
                    .where(qSalary.year.eq(year).and(qSalary.month.eq(month))
                            .and(qSalary.accountSetsId.eq(accountSetsId))
                            .and(qSalaryVoucher.id.isNull()))
                    .limit(500).fetch();
        } catch (Exception e) {
            log.warn("carryover-checks unBoundSalaryIds query failed", e);
            return Collections.emptyList();
        }
    }

    /** package-private hook：未生成凭证的 invoice ID 明细 */
    List<Long> fetchUnBoundInvoiceIdsForTest(Long accountSetsId, Integer year, Integer month) {
        try {
            LocalDate start = LocalDate.of(year, month, 1);
            LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
            return bqf.selectFrom(qInvoice).select(qInvoice.id)
                    .where(qInvoice.accountSetsId.eq(accountSetsId)
                            // FN-FIX-OPENAPI-INVOICE-PERIOD-ASSIGNMENT-DATE：期间过滤改用 assignmentDate 优先 + tradeDate 兜底
                            .and(InvoicePeriodPredicate.byPeriod(qInvoice, start, end))
                            .and(qInvoice.voucherId.isNull()))
                    .limit(500).fetch();
        } catch (Exception e) {
            log.warn("carryover-checks unBoundInvoiceIds query failed", e);
            return Collections.emptyList();
        }
    }

    /** package-private hook：未审凭证 ID 明细 */
    List<Long> fetchPendingVoucherIdsForTest(Long accountSetsId, Integer year, Integer month) {
        try {
            return bqf.selectFrom(qVoucher).select(qVoucher.id)
                    .where(qVoucher.accountSetsId.eq(accountSetsId)
                            .and(qVoucher.year.eq(year))
                            .and(qVoucher.month.eq(month))
                            .and(qVoucher.auditMemberId.isNull()))
                    .limit(500).fetch();
        } catch (Exception e) {
            log.warn("carryover-checks pendingVoucherIds query failed", e);
            return Collections.emptyList();
        }
    }

    /**
     * package-private hook：银行流水文件 + 未做账流水条数。
     * 返回 [hasFileCount, unBoundDetailCount] — 失败时返 [0, 0]
     */
    int[] fetchBankStatementCountsForTest(Long accountSetsId, Integer year, Integer month) {
        try {
            LocalDate period = LocalDate.of(year, month, 1);
            long fileCount = bqf.selectFrom(qFileInfo)
                    .where(qFileInfo.accountSetsId.eq(accountSetsId)
                            .and(qFileInfo.type.eq("bankStatement"))
                            .and(qFileInfo.accountSetPeriod.eq(period))
                            .and(qFileInfo.isDelete.eq(0).or(qFileInfo.isDelete.isNull())))
                    .fetchCount();
            java.time.LocalDateTime startT = period.atStartOfDay();
            java.time.LocalDateTime endT = period.withDayOfMonth(period.lengthOfMonth()).atTime(23, 59, 59);
            long unBoundCount = bqf.selectFrom(qBsd)
                    .where(qBsd.accountSetId.eq(accountSetsId)
                            .and(qBsd.tradeDate.between(startT, endT))
                            .and(qBsd.voucherId.isNull())
                            .and(qBsd.isDelete.eq(0).or(qBsd.isDelete.isNull())))
                    .fetchCount();
            return new int[]{(int) fileCount, (int) unBoundCount};
        } catch (Exception e) {
            log.warn("carryover-checks bank statement count query failed", e);
            return new int[]{0, 0};
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-FIX-OPENAPI-CHECKTPL §1  结转模板查询（xiaowei 生成结转凭证用）
    // GET /openapi/v1/checkout/templates?accountSetsId=88&year=2026&month=5
    //
    // 直接复用 CheckoutTemplateService.tplShowData()，给 xiaowei：
    //   - 当期所有结转模板（含 id / title / 派生 code / amount / voucherId）
    //   - voucherId 非空 = 已生成凭证（financial 网页同步显示"已生成 [查看]"）
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/templates")
    public JsonResult templates(@RequestParam Long accountSetsId,
                                @RequestParam Integer year,
                                @RequestParam Integer month) {
        if (accountSetsId == null) return JsonResult.failure("accountSetsId 不能为空");
        if (year == null || month == null) return JsonResult.failure("year/month 不能为空");

        AccountSets accountSets = findAccountSetForTest(accountSetsId);
        if (accountSets == null) return JsonResult.failure("账套不存在：" + accountSetsId);

        Long localCurrencyId;
        try {
            localCurrencyId = currencyService.fetchLocal(accountSetsId).getId();
        } catch (Exception e) {
            return failureWithType("加载本位币失败：" + e.getMessage(), "INTERNAL_ERROR");
        }

        Map<Long, Dict> raw;
        try {
            raw = checkoutTemplateService.tplShowData(accountSetsId, localCurrencyId, year, month);
        } catch (Exception e) {
            log.error("openapi checkout/templates tplShowData 失败", e);
            return failureWithType("加载结转模板失败：" + e.getMessage(), "INTERNAL_ERROR");
        }

        List<Map<String, Object>> templates = new ArrayList<>();
        if (raw != null) {
            for (Map.Entry<Long, Dict> entry : raw.entrySet()) {
                Long tplId = entry.getKey();
                Dict d = entry.getValue();
                if (d == null) continue;
                Map<String, Object> t = new LinkedHashMap<>();
                String title = d.getStr("title");
                t.put("id", tplId);
                t.put("title", title);
                String derived = deriveTemplateCode(title);
                t.put("code", derived);                     // 派生 code（实体无 code 列）— 老字段保留
                t.put("derivedCode", derived);              // FN-VERIFY-DEFAULT-CHECKOUT-TEMPLATES-V5: 新字段让 xiaowei 直接用
                t.put("amount", d.get("amount"));
                t.put("voucherId", d.get("voucherId"));      // 非空 = 已生成
                t.put("generated", d.get("voucherId") != null);
                t.put("subject", d.get("subject"));
                templates.add(t);
            }
            // 按 id 排序，保证返回顺序稳定
            templates.sort(Comparator.comparing(m -> ((Number) m.get("id")).longValue()));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountSetsId", accountSetsId);
        data.put("year", year);
        data.put("month", month);
        data.put("templates", templates);
        return JsonResult.successful(data);
    }

    /**
     * 根据模板 title 派生稳定的 code（CheckoutTemplate 实体无 code 列）。
     * 未命中规则返 null，调用方可降级用 title 匹配。
     */
    private static String deriveTemplateCode(String title) {
        // 委托给 CheckoutTemplateService.deriveCode 单一真相源
        return CheckoutTemplateService.deriveCode(title);
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-VERIFY-CHECKOUT-TEMPLATES-GET-API-V5
    // GET /openapi/v1/checkout/templates/health-check?accountSetsId=N
    //
    // 只读巡检：返该账套是否有 6 张标准 derivedCode 模板（COST_CARRY / SURTAX_ACCRUAL /
    //   VAT_SETTLEMENT / INCOME_TAX_ACCRUAL / YEAR_PROFIT / YEAR_LOSS）。
    //   - FN 不主动写库；缺则提示运维去 db/manual-cleanup/V20260528_998_*.sql 手工补
    //   - 响应：{ accountSetsId, expectedStandardCodes, presentCodes, missingCodes,
    //            bareYearProfit (rename 候选), templates, healthy }
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/templates/health-check")
    public JsonResult healthCheckTemplates(@RequestParam Long accountSetsId) {
        if (accountSetsId == null) return JsonResult.failure("accountSetsId 不能为空");
        AccountSets acc = findAccountSetForTest(accountSetsId);
        if (acc == null) return JsonResult.failure("账套不存在：" + accountSetsId);
        try {
            Map<String, Object> result = checkoutTemplateService.healthCheckSixTemplates(accountSetsId);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("templates/health-check 失败 accountSetsId={}", accountSetsId, e);
            return failureWithType("巡检 6 张标准模板失败：" + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-FIX-OPENAPI-CHECKTPL §2  损益归零自检（xiaowei 生成结转凭证后调一次）
    // GET /openapi/v1/checkout/profit-check?accountSetsId=88&year=2026&month=5
    //
    // 全归零 → nonZeroSubjects=[] + 自动 updateStatus(year,month,1) + 返已结转的凭证 ID
    // 未归零 → nonZeroSubjects=[...] + status 保持 0
    // 与 POST /profit-carry 区别：本接口是 GET 自检（无 throws、无 errorType），
    // 给 xiaowei 在生成凭证后做轻量级"是否齐了"判断。
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/profit-check")
    @Transactional
    public JsonResult profitCheck(@RequestParam Long accountSetsId,
                                  @RequestParam Integer year,
                                  @RequestParam Integer month) {
        if (accountSetsId == null) return JsonResult.failure("accountSetsId 不能为空");
        if (year == null || month == null) return JsonResult.failure("year/month 不能为空");

        AccountSets accountSets = findAccountSetForTest(accountSetsId);
        if (accountSets == null) return JsonResult.failure("账套不存在：" + accountSetsId);

        // 校验前同步重算余额（与 /profit-carry 一致，避免读到滞后）
        try {
            balanceCalcService.calcSynchronization(accountSetsId, YearMonth.of(year, month));
        } catch (Exception e) {
            log.warn("openapi profit-check 校验前同步重算失败（不阻塞）：{}", e.getMessage());
        }

        List<ProfitCheckDto> remaining;
        try {
            remaining = checkoutService.profitCheck(accountSetsId, year, month);
        } catch (Exception e) {
            log.error("openapi profit-check 加载损益科目失败", e);
            return failureWithType("加载损益科目失败：" + e.getMessage(), "INTERNAL_ERROR");
        }

        List<ProfitCheckDto> nonZero = remaining == null ? Collections.emptyList()
                : remaining.stream()
                .filter(d -> d.getBalance() != null && d.getBalance().abs().compareTo(CENT) > 0)
                .toList();

        // FN-FIX-PL-CARRY-VERIFY-ONLY：profitItems 是 xiaowei 建 pl_carry 的科目全集源
        //   每行含 subjectId / subjectCode / fullName / balance (signed) / balanceDirection /
        //         auxiliaryDetailId (主科目=0，aux 拆分行=真值) / auxiliary (AcDetails 简化列表)
        //   nonZeroSubjects 同语义保留向后兼容（旧 BT/XW 消费者依赖此字段名）
        List<Map<String, Object>> profitItems = new ArrayList<>();
        for (ProfitCheckDto d : nonZero) {
            profitItems.add(profitItemRow(d));
        }
        // 旧字段 nonZeroSubjects（保持 5 个字段不变，避免破坏 BT 三 Tab 弹窗）
        List<Map<String, Object>> nonZeroList = new ArrayList<>();
        for (ProfitCheckDto d : nonZero) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subjectId", d.getSubjectId());
            m.put("subjectCode", d.getCode());
            m.put("fullName", d.getFullName());
            m.put("balance", d.getBalance());
            m.put("balanceDirection", d.getBalanceDirection());
            nonZeroList.add(m);
        }

        // 当期状态（用于判断是否自动推进）
        Checkout existing = findCheckout(accountSetsId, year, month);
        int curStatus = existing != null && existing.getStatus() != null ? existing.getStatus() : 0;
        boolean statusUpdated = false;
        int newStatus = curStatus;

        if (nonZero.isEmpty() && curStatus == 0) {
            // 全部归零 + 还没推进 → 自动 status 0→1
            try {
                checkoutService.updateStatus(accountSetsId, year, month, 1);
                statusUpdated = true;
                newStatus = 1;
            } catch (Exception e) {
                log.warn("openapi profit-check 自动 updateStatus 失败（不影响判断）：{}", e.getMessage());
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountSetsId", accountSetsId);
        data.put("year", year);
        data.put("month", month);
        data.put("allZeroed", nonZero.isEmpty());
        data.put("nonZeroCount", nonZero.size());
        data.put("nonZeroSubjects", nonZeroList);    // 旧字段，向后兼容
        data.put("profitItems", profitItems);        // 新字段：含 aux 维度，xiaowei 建 pl_carry 的科目全集
        data.put("currentStatus", curStatus);
        data.put("newStatus", newStatus);
        data.put("statusUpdated", statusUpdated);
        return JsonResult.successful(data);
    }

    /** FN-FIX-PL-CARRY-VERIFY-ONLY：profitItem 行序列化（含 aux 维度），供 xiaowei 建 pl_carry */
    private Map<String, Object> profitItemRow(ProfitCheckDto d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subjectId", d.getSubjectId());
        m.put("subjectCode", d.getCode());
        m.put("fullName", d.getFullName());
        m.put("balance", d.getBalance());                   // 带符号
        m.put("balanceDirection", d.getBalanceDirection());
        m.put("auxiliaryDetailId", d.getAuxiliaryDetailId() == null ? 0L : d.getAuxiliaryDetailId());
        // 辅助核算明细简化（仅取关键字段，避开 AcDetails 子类序列化爆炸）
        List<Map<String, Object>> auxList = new ArrayList<>();
        if (d.getAuxiliary() != null) {
            for (cn.gson.financial.kernel.model.entity.AcDetails ac : d.getAuxiliary()) {
                if (ac == null) continue;
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("id", ac.getId());
                a.put("categoryId", ac.getCategoryId());
                a.put("categoryType", ac.getCategoryType() == null ? null : ac.getCategoryType().name());
                a.put("code", ac.getCode());
                a.put("name", ac.getName());
                auxList.add(a);
            }
        }
        m.put("auxiliary", auxList);
        return m;
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-FIX-PL-CARRY-VERIFY-ONLY（X 方案）— profit-carry-auto 改 verify-close
    //
    // 旧行为（已废）：FN 自建 pl_carry 凭证 + Step2 自建年结，事后归零自检。
    // 新行为（X）：xiaowei 按 V5 pl_carry 模板建凭证并 push 给 FN（carry_forward=true），
    //               本接口仅做「核账 + 关账」：
    //                 ① cleanupStale（保留）— 同 autoDeleteExisting 控制
    //                 ② 同步重算余额
    //                 ③ profitCheck 复核 5/6 末级科目（含辅助拆分）
    //                 ④ 全归零 → updateStatus(1) → success
    //                 ⑤ 任一非零 → 回滚本期 carry_forward 凭证 + status=0 +
    //                            errorType=LOSS_PROFIT_NOT_ZEROED_AFTER_AUTO + nonZeroSubjects
    //               12 月 year_profit_carry 也由 xiaowei 建，FN 不再 Step2。
    //               对外契约（errorType / nonZeroSubjects / forceMode / dryRun）保持不变。
    //
    // 与 xiaowei 联调对：XW-NEW-PL-CARRY-BUILD-FROM-TEMPLATE
    // ═══════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════
    // P3-1 月末结转损益（修订版：仅校验 + 改状态，不生成凭证）
    // POST /openapi/v1/checkout/profit-carry
    //
    // 设计调整（参考 XIAOWEI_OPENAPI_PHASE3_REVISION.md）：
    //   原方案：financial 自动生成结转凭证 + 推进状态
    //   新方案：skill 端推业务凭证 + 自己生成结转凭证（标 carryForward=true）→
    //          调本接口校验所有损益类科目本月余额是否归零 → 通过则 status 0→1
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/profit-carry")
    @Transactional
    public JsonResult profitCarry(@RequestBody CheckoutReq req) {
        if (req.getAccountSetsId() == null || req.getYear() == null || req.getMonth() == null) {
            return JsonResult.failure("accountSetsId/year/month 不能为空");
        }
        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(req.getAccountSetsId()))
                .fetchFirst();
        if (accountSets == null) {
            return JsonResult.failure("账套不存在：" + req.getAccountSetsId());
        }

        // 状态前置检查
        Checkout existing = findCheckout(req.getAccountSetsId(), req.getYear(), req.getMonth());
        Integer curStatus = existing != null ? existing.getStatus() : 0;
        if (Integer.valueOf(2).equals(curStatus)) {
            return failureWithType("期间已结账，不允许重新结转", "PERIOD_CLOSED");
        }
        if (Integer.valueOf(1).equals(curStatus)) {
            return failureWithType("当期已结转过损益（status=1），无需重复操作", "ALREADY_CARRIED");
        }

        // 进入校验前先同步重算一次余额，避免读到滞后值
        try {
            balanceCalcService.calcSynchronization(req.getAccountSetsId(),
                    YearMonth.of(req.getYear(), req.getMonth()));
        } catch (Exception e) {
            log.warn("openapi profit-carry 校验前同步重算失败（不阻塞）：{}", e.getMessage());
        }

        // 校验：所有损益类（5xxx 收入/费用/成本类）末级科目本月余额是否归零
        List<ProfitCheckDto> remaining;
        try {
            remaining = checkoutService.profitCheck(req.getAccountSetsId(), req.getYear(), req.getMonth());
        } catch (Exception e) {
            log.error("openapi profitCheck 失败", e);
            return failureWithType("加载损益科目失败：" + e.getMessage(), "INTERNAL_ERROR");
        }

        List<ProfitCheckDto> nonZero = remaining == null ? Collections.emptyList()
                : remaining.stream()
                .filter(d -> d.getBalance() != null && d.getBalance().abs().compareTo(CENT) > 0)
                .toList();

        if (!nonZero.isEmpty()) {
            // skill 还没有推结转凭证（或推得不够）
            List<Map<String, Object>> nonZeroList = new ArrayList<>();
            for (ProfitCheckDto d : nonZero) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("subjectId", d.getSubjectId());
                m.put("subjectCode", d.getCode());
                m.put("fullName", d.getFullName());
                m.put("balance", d.getBalance());
                m.put("balanceDirection", d.getBalanceDirection());
                nonZeroList.add(m);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("errorType", "LOSS_PROFIT_NOT_CARRIED");
            data.put("nonZeroCount", nonZero.size());
            data.put("nonZeroSubjects", nonZeroList);
            return JsonResult.failure("以下损益类科目本月余额未归零，请先生成结转凭证（标 carryForward=true）后重试，共 "
                    + nonZero.size() + " 项").setData(data);
        }

        // 全部归零 → 推进 status 0→1
        checkoutService.updateStatus(req.getAccountSetsId(), req.getYear(), req.getMonth(), 1);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("newStatus", 1);
        data.put("note", "损益类科目本月余额已全部归零，状态推进至 1");
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // P3-1b 月末结转损益 — financial 端自动生成凭证 + 推状态
    // POST /openapi/v1/checkout/profit-carry-auto
    //
    // 与 /profit-carry 的区别：
    //   /profit-carry        要求 XW 自己组结转凭证推过来，本接口只校验+推 status
    //   /profit-carry-auto   financial 端直接复用任务工作台的 TaskCarryForwardService
    //                        自动生成结转损益凭证（含本年利润分录），XW 一次调用搞定
    //
    // 行为（FN-FIX-PROFIT-CARRY-AUTO 后）：
    //   1. 入参校验 + 账套查找 + 状态前置（status=2 拒，status=1 拒）
    //   2. Bug C：删本期残留 carryForward=true 凭证 + 重算余额（防做账重跑叠加结转分录）
    //   3. 调 TaskCarryForwardService.execute() —— Step 0 结转模板 / Step 1 结转损益 / Step 2 12 月本年利润
    //   4. Bug B：重算余额 + 调 profitCheck 自检全部 5/6xxx 末级损益科目本期期末是否归零
    //      自检失败 → 删本次新增的 carryForward 凭证、强制 status=0、返回 LOSS_PROFIT_NOT_ZEROED_AFTER_AUTO
    //      自检通过 → 正常返回 voucherIds + newStatus=1
    //
    // 状态前置：status=2 → PERIOD_CLOSED；status=1 → ALREADY_CARRIED
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/profit-carry-auto")
    public JsonResult profitCarryAuto(@RequestBody CheckoutReq req) {
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

        // 状态前置检查（与 /profit-carry 一致）
        Checkout existing = findCheckout(req.getAccountSetsId(), req.getYear(), req.getMonth());
        Integer curStatus = existing != null ? existing.getStatus() : 0;
        if (Integer.valueOf(2).equals(curStatus)) {
            return failureWithType("期间已结账，不允许重新结转", "PERIOD_CLOSED");
        }
        if (Integer.valueOf(1).equals(curStatus)) {
            return failureWithType("当期已结转过损益（status=1），无需重复操作", "ALREADY_CARRIED");
        }

        // FN-CHECKOUT-API-PARITY：三个新入参，null 走默认行为（向后兼容）
        boolean autoDeleteExisting = req.getAutoDeleteExisting() == null || req.getAutoDeleteExisting();
        boolean forceMode = Boolean.TRUE.equals(req.getForceMode());
        boolean dryRun = Boolean.TRUE.equals(req.getDryRun());

        // ── FN-FIX-PROFIT-CARRY-AUTO Bug C：结转前清理本期残留 carryForward 凭证
        // 防止反复跑做账时叠加结转分录（章丘账套 5001/5401/5403 出现"反方向余额"的根因）。
        // 这里只删 carry_forward=true 的结转凭证，业务凭证 / 结转模板凭证不动。
        // FN-CHECKOUT-API-PARITY：autoDeleteExisting=false 时跳过清理（dryRun 也跳过，避免影响生产数据）
        int cleanedStale = 0;
        if (autoDeleteExisting && !dryRun) {
            cleanedStale = cleanupStaleCarryForwardVouchers(accountSets, req.getYear(), req.getMonth());
            if (cleanedStale > 0) {
                log.info("[FN-FIX-PROFIT-CARRY-AUTO] 账套 {} {}年{}月 清理残留 carryForward 凭证 {} 条",
                        req.getAccountSetsId(), req.getYear(), req.getMonth(), cleanedStale);
            }
        }

        // FN-CHECKOUT-API-PARITY：dryRun 模式 — 不真跑，仅快照模板预览 + 当前损益余额
        if (dryRun) {
            return doDryRun(accountSets, req.getYear(), req.getMonth(), cleanedStale);
        }

        // FN-FIX-PL-CARRY-VERIFY-ONLY（X 方案）：FN 不再自建 pl_carry 凭证 / 不再 Step2 年结。
        //   假设 xiaowei 已按 V5 pl_carry 模板 push 完结转凭证（carry_forward=true）。
        //   本接口仅扮演「核账」角色：查 carry_forward 凭证 → 重算 → profitCheck 复核。
        Map<String, Object> execResult = new LinkedHashMap<>();
        execResult.put("status", "verified");
        execResult.put("msg", "verify-only: xiaowei 已建 pl_carry，FN 仅核账");

        // 查询本期 carry_forward 凭证 ID 列表（xiaowei 已 push 的 + 模板触发的）
        List<Long> voucherIds = voucherService
                .loadProfitCheck(req.getAccountSetsId(), req.getYear(), req.getMonth())
                .stream().map(Voucher::getId).toList();

        // ── 核账（FN-FIX-PROFIT-CARRY-AUTO Bug B 自检路径，逻辑保留）─
        // 重算余额 + 调 profitCheck（按 5/6xxx 末级科目 + 辅助拆分）
        // 任一损益科目本期期末 ≠ 0 → 视为「xiaowei 建的不全 / 数据有问题」→ 回滚并报错
        try {
            balanceCalcService.calcSynchronization(req.getAccountSetsId(),
                    YearMonth.of(req.getYear(), req.getMonth()));
        } catch (Exception e) {
            log.warn("[FN-FIX-PL-CARRY-VERIFY-ONLY] 自检前同步重算失败（不阻塞）：{}", e.getMessage());
        }
        List<ProfitCheckDto> remaining;
        try {
            remaining = checkoutService.profitCheck(req.getAccountSetsId(), req.getYear(), req.getMonth());
        } catch (Exception e) {
            log.error("[FN-FIX-PL-CARRY-VERIFY-ONLY] 自检 profitCheck 失败", e);
            return failureWithType("结转后自检失败：" + e.getMessage(), "INTERNAL_ERROR");
        }
        List<ProfitCheckDto> nonZero = remaining == null ? Collections.emptyList()
                : remaining.stream()
                .filter(d -> d.getBalance() != null && d.getBalance().abs().compareTo(CENT) > 0)
                .toList();

        if (!nonZero.isEmpty()) {
            List<Map<String, Object>> nonZeroList = new ArrayList<>();
            for (ProfitCheckDto d : nonZero) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("subjectId", d.getSubjectId());
                m.put("subjectCode", d.getCode());
                m.put("fullName", d.getFullName());
                m.put("balance", d.getBalance());
                m.put("balanceDirection", d.getBalanceDirection());
                nonZeroList.add(m);
            }

            // FN-CHECKOUT-API-PARITY：forceMode=true → 保留凭证 + 推 status=1，仍 success=true 但带 warning
            if (forceMode) {
                Checkout afterCheckoutForce = findCheckout(req.getAccountSetsId(), req.getYear(), req.getMonth());
                Integer newStatusForce = afterCheckoutForce != null ? afterCheckoutForce.getStatus() : curStatus;
                // 如果 execute 没把 status 推到 1（例如 skipped），forceMode 这里补推
                if (newStatusForce == null || newStatusForce < 1) {
                    try {
                        checkoutService.updateStatus(req.getAccountSetsId(), req.getYear(), req.getMonth(), 1);
                        newStatusForce = 1;
                    } catch (Exception e) {
                        log.error("[FN-CHECKOUT-API-PARITY] forceMode 推 status=1 异常", e);
                    }
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("accountSetsId", req.getAccountSetsId());
                data.put("year", req.getYear());
                data.put("month", req.getMonth());
                data.put("status", execResult.get("status"));
                data.put("msg", execResult.get("msg"));
                data.put("voucherIds", voucherIds);
                data.put("cleanedStaleCount", cleanedStale);
                data.put("forced", true);
                data.put("warningType", "FORCED_WITH_NONZERO");
                data.put("nonZeroCount", nonZero.size());
                data.put("nonZeroSubjects", nonZeroList);
                data.put("newStatus", newStatusForce);
                return JsonResult.successful(data);
            }

            // 默认行为：自检失败 → 回滚本次新增的 carryForward 凭证 + 强制 status=0
            if (!voucherIds.isEmpty()) {
                try {
                    voucherService.batchDelete(accountSets, voucherIds);
                    balanceCalcService.calcSynchronization(req.getAccountSetsId(),
                            YearMonth.of(req.getYear(), req.getMonth()));
                } catch (Exception e) {
                    log.error("[FN-FIX-PROFIT-CARRY-AUTO] 自检失败后回滚凭证异常（仍会重置 status）", e);
                }
            }
            // TaskCarryForwardService 在结转成功后会推 status=1，自检失败时强制回 0
            try {
                checkoutService.updateStatus(req.getAccountSetsId(), req.getYear(), req.getMonth(), 0);
            } catch (Exception e) {
                log.error("[FN-FIX-PROFIT-CARRY-AUTO] 自检失败后重置 status 异常", e);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountSetsId", req.getAccountSetsId());
            data.put("year", req.getYear());
            data.put("month", req.getMonth());
            data.put("errorType", "LOSS_PROFIT_NOT_ZEROED_AFTER_AUTO");
            data.put("nonZeroCount", nonZero.size());
            data.put("nonZeroSubjects", nonZeroList);
            data.put("rolledBackVoucherIds", voucherIds);
            data.put("newStatus", 0);
            return JsonResult.failure("结转后仍有 " + nonZero.size()
                    + " 个损益科目余额未归零，已回滚本次结转凭证；请检查账务（科目类别 / 重复凭证）后重试").setData(data);
        }

        // FN-FIX-PL-CARRY-VERIFY-ONLY：核账通过（全部归零） → 关账 status=1
        // 旧版 execute() 内部会推 status；新版 verify-only 必须由本接口显式推。
        int newStatus = curStatus;
        try {
            if (curStatus < 1) {
                checkoutService.updateStatus(req.getAccountSetsId(), req.getYear(), req.getMonth(), 1);
            }
            Checkout afterCheckout = findCheckout(req.getAccountSetsId(), req.getYear(), req.getMonth());
            newStatus = afterCheckout != null && afterCheckout.getStatus() != null
                    ? afterCheckout.getStatus() : 1;
        } catch (Exception e) {
            log.error("[FN-FIX-PL-CARRY-VERIFY-ONLY] 核账通过后推 status=1 异常", e);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountSetsId", req.getAccountSetsId());
        data.put("year", req.getYear());
        data.put("month", req.getMonth());
        data.put("status", execResult.get("status"));      // verified
        data.put("msg", execResult.get("msg"));
        data.put("voucherIds", voucherIds);
        data.put("cleanedStaleCount", cleanedStale);
        data.put("newStatus", newStatus);
        return JsonResult.successful(data);
    }

    /**
     * FN-FIX-PROFIT-CARRY-AUTO：清理本期残留的「结转损益」凭证。
     * <p>
     * 命中条件：{@code carry_forward=true AND check_tpl_id IS NULL}
     * —— 只匹配 Step 1 内置结转损益生成的凭证（buildCarryForwardVoucher 不 set checkTplId）。
     * <p>
     * <b>不会</b>误删：
     * <ul>
     *   <li>业务凭证（{@code carry_forward=false}）</li>
     *   <li>Step 0 模板凭证（计提税金 / 结转销售成本 / 结转未交增值税 / 计提所得税）—— 它们虽然
     *       也带 {@code carry_forward=true}，但同时带 {@code check_tpl_id}，被排除在外。
     *       保留 Step 0 模板凭证很关键：execute Step 1 算损益余额需要它们已落账的结果。</li>
     * </ul>
     *
     * @return 实际清掉的凭证数
     */
    private int cleanupStaleCarryForwardVouchers(AccountSets accountSets, Integer year, Integer month) {
        List<Long> staleIds = loadStaleCarryForwardIdsForTest(accountSets.getId(), year, month);
        if (staleIds.isEmpty()) return 0;
        voucherService.batchDelete(accountSets, staleIds);
        try {
            balanceCalcService.calcSynchronization(accountSets.getId(), YearMonth.of(year, month));
        } catch (Exception e) {
            log.warn("[FN-FIX-PROFIT-CARRY-AUTO] 清理残留 carryForward 凭证后重算余额失败：{}", e.getMessage());
        }
        return staleIds.size();
    }

    /**
     * package-private test hook：单测覆写避开 BlazeJPAQuery 直查。
     * <p>查询条件 {@code carry_forward=true AND check_tpl_id IS NULL}，严格对齐
     * Step 1 内置结转损益凭证（与 {@link VoucherService#loadProfitCheck} 同语义），
     * 不会误删 Step 0 模板凭证。
     */
    List<Long> loadStaleCarryForwardIdsForTest(Long accountSetsId, Integer year, Integer month) {
        return bqf.selectFrom(qVoucher)
                .select(qVoucher.id)
                .where(qVoucher.accountSetsId.eq(accountSetsId)
                        .and(qVoucher.year.eq(year))
                        .and(qVoucher.month.eq(month))
                        .and(qVoucher.carryForward.isTrue())
                        .and(qVoucher.checkTplId.isNull()))
                .fetch();
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-CHECKOUT-API-PARITY：dryRun 模式 — 不真跑 execute，只快照
    // 返回：当前 checkout 状态、模板预览、当前损益余额、残留 carryForward 数
    // ═══════════════════════════════════════════════════════════════
    private JsonResult doDryRun(AccountSets accountSets, Integer year, Integer month, int cleanedStale) {
        Long accountSetsId = accountSets.getId();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountSetsId", accountSetsId);
        data.put("year", year);
        data.put("month", month);
        data.put("dryRun", true);

        // 当前 checkout 状态
        Checkout existing = findCheckout(accountSetsId, year, month);
        data.put("currentStatus", existing != null && existing.getStatus() != null ? existing.getStatus() : 0);

        // 残留 carryForward 凭证数（dryRun 不会真删，但显示给 XW 看会被删多少）
        try {
            List<Long> staleIds = loadStaleCarryForwardIdsForTest(accountSetsId, year, month);
            data.put("staleCarryForwardCount", staleIds.size());
            data.put("staleCarryForwardIds", staleIds);
        } catch (Exception e) {
            log.warn("[dryRun] 加载残留 carryForward 凭证失败", e);
            data.put("staleCarryForwardCount", -1);
        }

        // 模板预览：复用 templates endpoint 的派生逻辑
        try {
            Long localCurrencyId = currencyService.fetchLocal(accountSetsId).getId();
            Map<Long, Dict> raw = checkoutTemplateService.tplShowData(accountSetsId, localCurrencyId, year, month);
            List<Map<String, Object>> templates = new ArrayList<>();
            if (raw != null) {
                for (Map.Entry<Long, Dict> entry : raw.entrySet()) {
                    Dict d = entry.getValue();
                    if (d == null) continue;
                    Map<String, Object> t = new LinkedHashMap<>();
                    String title = d.getStr("title");
                    Object voucherId = d.get("voucherId");
                    Object amountObj = d.get("amount");
                    BigDecimal amount = amountObj instanceof BigDecimal ? (BigDecimal) amountObj
                            : (amountObj instanceof Number ? new BigDecimal(amountObj.toString()) : BigDecimal.ZERO);
                    t.put("id", entry.getKey());
                    t.put("title", title);
                    t.put("amount", amount);
                    t.put("voucherId", voucherId);
                    boolean willGenerate;
                    String reason;
                    if (voucherId != null) {
                        willGenerate = false;
                        reason = "已有凭证 (voucherId=" + voucherId + ")";
                    } else if (title != null && title.contains("结转损益")) {
                        willGenerate = false;
                        reason = "结转损益由 Step 1 内置生成，模板不走";
                    } else if (title != null && title.contains("本年利润")) {
                        willGenerate = month != null && month == 12;
                        reason = month != null && month == 12 ? "12 月由 Step 2 单独处理" : "仅 12 月生成";
                    } else if (amount.compareTo(BigDecimal.ZERO) == 0) {
                        willGenerate = false;
                        reason = "金额=0 跳过";
                    } else if (title != null && title.contains("计提") && amount.compareTo(BigDecimal.ZERO) < 0) {
                        willGenerate = false;
                        reason = "计提金额为负 跳过";
                    } else {
                        willGenerate = true;
                        reason = "金额>0 且无现有凭证 → 将生成";
                    }
                    t.put("willGenerate", willGenerate);
                    t.put("reason", reason);
                    templates.add(t);
                }
                templates.sort(Comparator.comparing(m -> ((Number) m.get("id")).longValue()));
            }
            data.put("templates", templates);
        } catch (Exception e) {
            log.warn("[dryRun] 加载模板预览失败", e);
            data.put("templates", Collections.emptyList());
            data.put("templatesError", e.getMessage());
        }

        // 当前损益科目余额快照（未执行结转前）
        try {
            balanceCalcService.calcSynchronization(accountSetsId, YearMonth.of(year, month));
        } catch (Exception e) {
            log.warn("[dryRun] 同步重算余额失败（不阻塞）：{}", e.getMessage());
        }
        try {
            List<ProfitCheckDto> remaining = checkoutService.profitCheck(accountSetsId, year, month);
            List<ProfitCheckDto> nonZero = remaining == null ? Collections.emptyList()
                    : remaining.stream()
                    .filter(d -> d.getBalance() != null && d.getBalance().abs().compareTo(CENT) > 0)
                    .toList();
            List<Map<String, Object>> nonZeroList = new ArrayList<>();
            for (ProfitCheckDto d : nonZero) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("subjectId", d.getSubjectId());
                m.put("subjectCode", d.getCode());
                m.put("fullName", d.getFullName());
                m.put("balance", d.getBalance());
                m.put("balanceDirection", d.getBalanceDirection());
                nonZeroList.add(m);
            }
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("nonZeroCount", nonZero.size());
            snap.put("nonZeroSubjects", nonZeroList);
            data.put("profitCheckSnapshotBefore", snap);
        } catch (Exception e) {
            log.warn("[dryRun] profitCheck 快照失败", e);
            data.put("profitCheckSnapshotBefore", Collections.emptyMap());
        }

        data.put("cleanedStaleCount", cleanedStale);   // dryRun 下永远 0，仅保留字段对齐
        data.put("note", "dryRun=true，未真跑结转，未改库");
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-CHECKOUT-API-PARITY：诊断 endpoint — 给 XW 排查模板配置 / 余额 / 残留凭证
    // GET /openapi/v1/checkout/profit-carry/diagnose?accountSetsId=88&year=2026&month=3
    // 与 dryRun 的区别：纯只读，不调 calcSynchronization 也不动 status，最快返回。
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/profit-carry/diagnose")
    public JsonResult profitCarryDiagnose(@RequestParam Long accountSetsId,
                                          @RequestParam Integer year,
                                          @RequestParam Integer month) {
        if (accountSetsId == null) return JsonResult.failure("accountSetsId 不能为空");
        if (year == null || month == null) return JsonResult.failure("year/month 不能为空");

        AccountSets accountSets = findAccountSetForTest(accountSetsId);
        if (accountSets == null) return JsonResult.failure("账套不存在：" + accountSetsId);

        // 复用 doDryRun 的快照逻辑（cleanedStale 标 0 表示"诊断未做清理"）
        return doDryRun(accountSets, year, month, 0);
    }

    // ═══════════════════════════════════════════════════════════════
    // P3-2 年终结转利润（修订版：仅校验 4103 归零，不生成凭证、不改状态）
    // POST /openapi/v1/checkout/year-end-carry
    //
    // 设计调整：年终结转凭证由 skill 端自己生成（标 carryForward=true），
    // 本接口仅做"4103 本年利润 12 月期末是否归零"的校验，归零即返回成功。
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/year-end-carry")
    @Transactional
    public JsonResult yearEndCarry(@RequestBody CheckoutReq req) {
        if (req.getAccountSetsId() == null || req.getYear() == null) {
            return JsonResult.failure("accountSetsId/year 不能为空");
        }
        if (req.getMonth() != null && req.getMonth() != 12) {
            return failureWithType("年终结转只能在 12 月调用", "NOT_DECEMBER");
        }
        Integer month = 12;

        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(req.getAccountSetsId()))
                .fetchFirst();
        if (accountSets == null) {
            return JsonResult.failure("账套不存在：" + req.getAccountSetsId());
        }

        // 12 月 status 必须 >= 1（已经做过 profit-carry）
        Checkout c = findCheckout(req.getAccountSetsId(), req.getYear(), month);
        Integer curStatus = c != null ? c.getStatus() : 0;
        if (Integer.valueOf(2).equals(curStatus)) {
            return failureWithType("12 月已结账，不允许重新校验年终结转", "PERIOD_CLOSED");
        }
        if (curStatus == null || curStatus < 1) {
            return failureWithType("必须先调 /profit-carry 完成 12 月损益结转，再做年终结转校验",
                    "PROFIT_CARRY_NOT_DONE");
        }

        // 找本年利润科目
        Subject yearProfitSubject = subjectService.loadByName("本年利润", req.getAccountSetsId());
        if (yearProfitSubject == null) {
            return failureWithType("账套未配置「本年利润」科目", "YEAR_PROFIT_SUBJECT_NOT_FOUND");
        }
        if (!Boolean.TRUE.equals(yearProfitSubject.getLeaf())) {
            yearProfitSubject = subjectService.loadFirstLeafChildren(yearProfitSubject);
        }

        // 进入余额检查前先同步重算一次，避免读到滞后值
        try {
            balanceCalcService.calcSynchronization(req.getAccountSetsId(), YearMonth.of(req.getYear(), 12));
        } catch (Exception e) {
            log.warn("openapi year-end-carry 校验前同步重算失败（不阻塞）：{}", e.getMessage());
        }

        // BUG B-8：必须限定本位币，否则会拿到 currency_id=0 那条（值不准）
        Long localCurrencyId = currencyService.fetchLocal(req.getAccountSetsId()).getId();

        // 查 12 月本年利润科目的 end_balance（本位币）
        BigDecimal yearProfitBalance = bqf.selectFrom(qBalance)
                .select(qBalance.endBalance)
                .where(qBalance.accountSetsId.eq(req.getAccountSetsId())
                        .and(qBalance.subjectId.eq(yearProfitSubject.getId()))
                        .and(qBalance.auxiliaryDetailId.eq(0L))
                        .and(qBalance.currencyId.eq(localCurrencyId))
                        .and(qBalance.year.eq(req.getYear()))
                        .and(qBalance.period.eq(month)))
                .fetchFirst();

        if (yearProfitBalance == null) yearProfitBalance = BigDecimal.ZERO;

        if (yearProfitBalance.abs().compareTo(CENT) <= 0) {
            // 已归零 → 通过校验
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("balanced", true);
            data.put("yearProfitBalance", BigDecimal.ZERO);
            data.put("newStatus", 1);
            data.put("note", "4103 本年利润已归零，可继续调用 /checkout 完成结账");
            return JsonResult.successful(data);
        }

        // 未归零 → 要求 skill 先推年终结转凭证
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorType", "YEAR_END_NOT_CARRIED");
        data.put("yearProfitBalance", yearProfitBalance);
        data.put("yearProfitSubjectId", yearProfitSubject.getId());
        data.put("yearProfitSubjectCode", yearProfitSubject.getCode());
        return JsonResult.failure("4103 本年利润 12 月期末余额未归零（=" + yearProfitBalance
                + "），请先生成年终结转凭证（标 carryForward=true）后重试").setData(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // P3-3 实际结账
    // POST /openapi/v1/checkout
    // ═══════════════════════════════════════════════════════════════

    @PostMapping
    @Transactional
    public JsonResult invoicing(@RequestBody CheckoutReq req) {
        if (req.getAccountSetsId() == null || req.getYear() == null || req.getMonth() == null) {
            return JsonResult.failure("accountSetsId/year/month 不能为空");
        }

        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(req.getAccountSetsId()))
                .fetchFirst();
        if (accountSets == null) {
            return JsonResult.failure("账套不存在：" + req.getAccountSetsId());
        }

        Checkout c = findCheckout(req.getAccountSetsId(), req.getYear(), req.getMonth());
        Integer curStatus = c != null ? c.getStatus() : 0;
        if (Integer.valueOf(2).equals(curStatus)) {
            return failureWithType("期间已结账", "ALREADY_CHECKED_OUT");
        }
        if (curStatus == null || curStatus < 1) {
            return failureWithType("尚未结转损益（status=0），先调 /profit-carry", "PROFIT_CARRY_NOT_DONE");
        }

        // 12 月特殊：年终结转后本年利润应该归零
        if (req.getMonth() == 12) {
            // BUG B-6：进入 4103 余额检查前先同步重算一次，避免余额表落后于最新凭证
            try {
                balanceCalcService.calcSynchronization(req.getAccountSetsId(), YearMonth.of(req.getYear(), 12));
            } catch (Exception e) {
                log.warn("openapi invoicing 前同步重算余额失败（不阻塞）：{}", e.getMessage());
            }

            Subject yearProfit = subjectService.loadByName("本年利润", req.getAccountSetsId());
            if (yearProfit != null) {
                if (!Boolean.TRUE.equals(yearProfit.getLeaf())) {
                    yearProfit = subjectService.loadFirstLeafChildren(yearProfit);
                }
                // BUG B-8：必须限定本位币，否则 currency_id=0 那条数据不准导致误判
                Long localCurrencyId = currencyService.fetchLocal(req.getAccountSetsId()).getId();
                BigDecimal endBal = bqf.selectFrom(qBalance)
                        .select(qBalance.endBalance)
                        .where(qBalance.accountSetsId.eq(req.getAccountSetsId())
                                .and(qBalance.subjectId.eq(yearProfit.getId()))
                                .and(qBalance.auxiliaryDetailId.eq(0L))
                                .and(qBalance.currencyId.eq(localCurrencyId))
                                .and(qBalance.year.eq(req.getYear()))
                                .and(qBalance.period.eq(12)))
                        .fetchFirst();
                if (endBal != null && endBal.abs().compareTo(CENT) > 0) {
                    return failureWithType("12 月本年利润余额不为 0（=" + endBal + "），需先调 /year-end-carry",
                            "YEAR_END_CARRY_NOT_DONE");
                }
            }
        }

        // 实际结账：复用现有 service
        try {
            checkoutService.invoicing(accountSets, req.getYear(), req.getMonth());
        } catch (Exception e) {
            log.error("openapi 结账失败", e);
            String msg = e.getMessage() != null ? e.getMessage() : "结账失败";
            String type = msg.contains("借贷") && (msg.contains("不平") || msg.contains("不相等"))
                    ? "BALANCE_MISMATCH" : "INTERNAL_ERROR";
            return failureWithType(msg, type);
        }

        // 推进 currentAccountDate 到下个月 1 号（与内部 CheckOutController.invoicing 行为一致）
        LocalDate newCurrentAccountDate = LocalDate.of(req.getYear(), req.getMonth(), 1).plusMonths(1);
        accountSets.setCurrentAccountDate(newCurrentAccountDate);
        accountSetsService.update(accountSets);

        // 统计被锁定的凭证数（结账后 locked=true 的当期凭证）
        long lockedCount = bqf.selectFrom(cn.gson.financial.kernel.model.entity.QVoucher.voucher)
                .where(cn.gson.financial.kernel.model.entity.QVoucher.voucher.accountSetsId.eq(req.getAccountSetsId())
                        .and(cn.gson.financial.kernel.model.entity.QVoucher.voucher.year.eq(req.getYear()))
                        .and(cn.gson.financial.kernel.model.entity.QVoucher.voucher.month.eq(req.getMonth()))
                        .and(cn.gson.financial.kernel.model.entity.QVoucher.voucher.locked.isTrue()))
                .fetchCount();

        Checkout updated = findCheckout(req.getAccountSetsId(), req.getYear(), req.getMonth());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("newStatus", 2);
        data.put("newCurrentAccountDate", newCurrentAccountDate);
        data.put("checkoutDate", updated != null ? updated.getCheckDate() : LocalDate.now());
        data.put("lockedVoucherCount", (int) lockedCount);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════════

    private Checkout findCheckout(Long accountSetsId, Integer year, Integer month) {
        return findCheckoutForTest(accountSetsId, year, month);
    }

    /** package-private test hook：单测覆写以避开 BlazeJPAQuery 难 mock 的问题 */
    Checkout findCheckoutForTest(Long accountSetsId, Integer year, Integer month) {
        return bqf.selectFrom(qCheckout)
                .where(qCheckout.accountSetsId.eq(accountSetsId)
                        .and(qCheckout.checkYear.eq(year))
                        .and(qCheckout.checkMonth.eq(month)))
                .fetchFirst();
    }

    /** package-private test hook：单测覆写以避开 BlazeJPAQuery 难 mock 的问题 */
    AccountSets findAccountSetForTest(Long accountSetsId) {
        return bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(accountSetsId))
                .fetchFirst();
    }

    private static JsonResult failureWithType(String msg, String errorType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorType", errorType);
        return JsonResult.failure(msg).setData(data);
    }

    @Data
    public static class CheckoutReq {
        private Long accountSetsId;
        private Integer year;
        private Integer month;        // year-end-carry 时可省（内部强制 12）
        // FN-CHECKOUT-API-PARITY：profit-carry-auto 三个新入参（向后兼容，null 走默认行为）
        // autoDeleteExisting=null/true → 自动清本期 carryForward 残留（与现状一致）；false → 跳过清理
        private Boolean autoDeleteExisting;
        // forceMode=null/false → 自检不归零回滚 + 报错（与现状一致）；true → 不归零仍保留凭证 + 推 status=1
        private Boolean forceMode;
        // dryRun=null/false → 真落库（与现状一致）；true → 执行后回滚整段，仅返回快照
        private Boolean dryRun;
    }
}
