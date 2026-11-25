package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.crm.service.OutSystem2CRMService;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.entity.QAccountingCategory;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.vo.AccountBookQueryVO;
import cn.gson.financial.kernel.model.vo.FeeDetailQuery;
import cn.gson.financial.kernel.service.AccountBookService;
import cn.gson.financial.kernel.service.CashierJournalService;
import cn.gson.financial.kernel.service.InvoiceService;
import cn.gson.financial.kernel.service.ReportService;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工单 122 · L1 财务取数扩展 · 只读对外端点（薄封装既有内部 service，不新建查询逻辑）。
 * <p>
 * 路径前缀 {@code /openapi/v1/finance}，全部 {@code @SaIgnore}（沿 openapi 现有内网模式）。
 * 全部<b>账面口径</b>；空 / 账套不存在 → 诚实回传（沿 117 守卫思路），不阻断、不当 0。
 * <ul>
 *   <li>L1-AUX：往来对象级辅助核算 余额 / 明细（{@link AccountBookService#auxiliaryBalance}/{@link AccountBookService#auxiliaryDetails}）</li>
 *   <li>L1-INVOICE-AGG：进销项汇总 + 税率分布 + 红冲/作废 + topN（{@link InvoiceService#invoiceAggregate}）</li>
 *   <li>L1-FEE：费用明细（{@link ReportService#feeDetail}）</li>
 *   <li>L1-CASHIER：出纳日记账只读 + 资金合计（{@link CashierJournalService#list}/{@link CashierJournalService#total}）</li>
 *   <li>L1-MULTIPERIOD：多期科目余额（{@link OutSystem2CRMService#crmGetMultiPeriodBalance}）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/finance")
@SaIgnore
public class OpenApiFinanceExtController {

    private final AccountBookService accountBookService;
    private final InvoiceService invoiceService;
    private final ReportService reportService;
    private final CashierJournalService cashierJournalService;
    private final OutSystem2CRMService outSystem2CRMService;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QAccountSets qAccountSets = QAccountSets.accountSets;
    private static final QAccountingCategory qAccountingCategory = QAccountingCategory.accountingCategory;

    // ───────────────────────── L1-AUX ─────────────────────────

    /**
     * 往来对象级辅助核算<b>余额</b>（期初/借贷/期末，按客户/供应商/职员/项目）。
     * <p>body = {@link AccountBookQueryVO}（必含 accountSetsId + start/end）。
     * <b>辅助类别二选一</b>：传 {@code categoryType}（CUSTOMER/VENDOR/EMPLOYEE/PROJECT，推荐，内部解析该账套类别 id）
     * 或直接传 {@code categoryId}（账套内 accounting_category.id）。
     * 薄封装 {@code AccountBookController.auxiliaryBalance}（把 @SessionAccountSet 换为 body.accountSetsId，只加 type→categoryId 解析）。
     */
    @PostMapping("/auxiliary/balance")
    public JsonResult auxiliaryBalance(@RequestBody AccountBookQueryVO queryVO) {
        AccountSets as = loadAccountSets(queryVO.getAccountSetsId());
        if (as == null) return JsonResult.failure("账套不存在 accountSetsId=" + queryVO.getAccountSetsId());
        queryVO.setAccountSetsId(as.getId());
        String err = resolveCategory(queryVO);
        if (err != null) return JsonResult.failure(err);
        return JsonResult.successful(accountBookService.auxiliaryBalance(queryVO, as));
    }

    /**
     * 往来对象级辅助核算<b>明细</b>（逐笔，供挂账时长/账龄分析）。
     * 辅助类别同 {@code /auxiliary/balance}：传 {@code categoryType} 或 {@code categoryId}。
     * 薄封装 {@code AccountBookController.auxiliaryDetails}。
     */
    @PostMapping("/auxiliary/details")
    public JsonResult auxiliaryDetails(@RequestBody AccountBookQueryVO queryVO) {
        AccountSets as = loadAccountSets(queryVO.getAccountSetsId());
        if (as == null) return JsonResult.failure("账套不存在 accountSetsId=" + queryVO.getAccountSetsId());
        queryVO.setAccountSetsId(as.getId());
        String err = resolveCategory(queryVO);
        if (err != null) return JsonResult.failure(err);
        return JsonResult.successful(accountBookService.auxiliaryDetails(queryVO, as.getEnableDate()));
    }

    /**
     * 辅助类别解析：若传了 {@code categoryType}（且未直接给 categoryId），按 (accountSetsId, categoryType)
     * 查该账套的 accounting_category.id 回填 {@code categoryId}；service 内部仍按 categoryId 匹配辅助明细。
     *
     * @return 错误信息（缺类别 / 类别非法 / 该账套无此类别）；null 表示 OK
     */
    private String resolveCategory(AccountBookQueryVO queryVO) {
        // 已直接给 categoryId → 沿用（向后兼容），不强制 type
        if (cn.hutool.core.util.StrUtil.isNotBlank(queryVO.getCategoryId())) return null;
        AccountingCategoryType type = queryVO.getCategoryType();
        if (type == null) return "请传 categoryType（CUSTOMER/VENDOR/EMPLOYEE/PROJECT）或 categoryId";
        Long categoryId = bqf.selectFrom(qAccountingCategory)
                .select(qAccountingCategory.id)
                .where(qAccountingCategory.accountSetsId.eq(queryVO.getAccountSetsId())
                        .and(qAccountingCategory.categoryType.eq(type)))
                .fetchFirst();
        if (categoryId == null) return "该账套未配置辅助核算类别：" + type.name() + "（" + type.display + "）";
        queryVO.setCategoryId(categoryId.toString());
        return null;
    }

    // ───────────────────────── L1-INVOICE-AGG ─────────────────────────

    /**
     * 进销项汇总聚合：销项(OUT)+进项(IN) 各返 总计 / 按月 / 税率分布 / state 分布 / topN 客户供应商。
     * <p>账面口径，按 assignmentDate 认证/开具日过滤；start/end 可空（不限期）。
     */
    @GetMapping("/invoice/aggregate")
    public JsonResult invoiceAggregate(@RequestParam Long accountSetsId,
                                       @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate start,
                                       @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate end,
                                       @RequestParam(required = false, defaultValue = "10") Integer topN) {
        if (loadAccountSets(accountSetsId) == null) return JsonResult.failure("账套不存在 accountSetsId=" + accountSetsId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountSetsId", accountSetsId);
        data.put("out", invoiceService.invoiceAggregate(InvoiceType.OUT, accountSetsId, start, end, topN));
        data.put("in", invoiceService.invoiceAggregate(InvoiceType.IN, accountSetsId, start, end, topN));
        return JsonResult.successful(data);
    }

    // ───────────────────────── L1-FEE ─────────────────────────

    /**
     * 费用明细（各费用科目本年累计 + 占比 + 业务类别），给 119 L2-FEE 升级数据源。
     * <p>body = {@link FeeDetailQuery}（start/end + 可选 subjectId 集合）；accountSetsId 走 query 参数。
     */
    @PostMapping("/fee/detail")
    public JsonResult feeDetail(@RequestParam Long accountSetsId, @RequestBody FeeDetailQuery query) {
        AccountSets as = loadAccountSets(accountSetsId);
        if (as == null) return JsonResult.failure("账套不存在 accountSetsId=" + accountSetsId);
        return JsonResult.successful(reportService.feeDetail(as, query));
    }

    // ───────────────────────── L1-CASHIER ─────────────────────────

    /**
     * 出纳日记账列表（只读，资金流水：收支/对方科目/往来对象/余额）。
     * 薄封装 {@code CashierJournalService.list}；accountId 为出纳账户 ID（必填，与内部一致）。
     */
    @PostMapping("/cashier/list")
    public JsonResult cashierList(@RequestParam Long accountSetsId, @RequestParam Long accountId,
                                  Page page, @RequestBody CashierJournalService.CashierJournalQuery query) {
        if (loadAccountSets(accountSetsId) == null) return JsonResult.failure("账套不存在 accountSetsId=" + accountSetsId);
        return JsonResult.successful(cashierJournalService.list(accountSetsId, accountId, page, query));
    }

    /**
     * 出纳资金合计（借贷合计）。薄封装 {@code CashierJournalService.total}。
     */
    @PostMapping("/cashier/total")
    public JsonResult cashierTotal(@RequestParam Long accountSetsId, @RequestParam Long accountId,
                                   @RequestBody CashierJournalService.CashierJournalQuery query) {
        if (loadAccountSets(accountSetsId) == null) return JsonResult.failure("账套不存在 accountSetsId=" + accountSetsId);
        return JsonResult.successful(cashierJournalService.total(accountSetsId, accountId, query));
    }

    /**
     * 出纳日记账<b>全部账户</b>列表 + 合计（<b>免 accountId</b>，跨所有出纳账户）。
     * 薄封装 {@code CashierJournalService.multipleList(:214)} + {@code multipleTotal}；
     * body = {@link CashierJournalService.CashierJournalMultipleQuery}（可按 account 集合/日期/金额过滤，全空=全部）。
     * <p>返回 {@code { list:PageResults, total:{debit,credit,balance} }}，供资金流↔收入勾稽、公私资金往来识别。
     */
    @PostMapping("/cashier/all")
    public JsonResult cashierAll(@RequestParam Long accountSetsId, Page page,
                                 @RequestBody CashierJournalService.CashierJournalMultipleQuery query) {
        if (loadAccountSets(accountSetsId) == null) return JsonResult.failure("账套不存在 accountSetsId=" + accountSetsId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", cashierJournalService.multipleList(accountSetsId, page, query));
        data.put("total", cashierJournalService.multipleTotal(accountSetsId, query));
        return JsonResult.successful(data);
    }

    // ───────────────────────── L1-MULTIPERIOD ─────────────────────────

    /**
     * 多期/区间科目余额（一次取 startPeriod~endPeriod 多期），替 finmaster 116 buildTrends 逐期轮询。
     */
    @GetMapping("/balance/multi-period")
    public JsonResult multiPeriodBalance(@RequestParam Long accountSetsId,
                                         @RequestParam String startPeriod,
                                         @RequestParam String endPeriod) {
        return JsonResult.successful(outSystem2CRMService.crmGetMultiPeriodBalance(accountSetsId, startPeriod, endPeriod));
    }

    // ───────────────────────── 辅助 ─────────────────────────

    private AccountSets loadAccountSets(Long id) {
        if (id == null) return null;
        return bqf.selectFrom(qAccountSets).where(qAccountSets.id.eq(id)).fetchFirst();
    }
}
