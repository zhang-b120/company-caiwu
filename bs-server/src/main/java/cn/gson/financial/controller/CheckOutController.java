package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import cn.gson.financial.kernel.model.dto.FinancialProcessDTO;
import cn.gson.financial.kernel.service.FinancialProcessService;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.dto.ProfitCheckDto;
import cn.gson.financial.kernel.model.dto.ReportItemsDto;
import cn.gson.financial.kernel.model.dto.SubjectDto;
import cn.gson.financial.kernel.model.dto.TrialBalanceDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.entity.Voucher;
import cn.gson.financial.kernel.model.vo.CashierAccountCheckVo;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.*;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.NumberUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static cn.gson.financial.kernel.common.Constants.S_USER_KEY;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年09月03日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@RestController
@RequestMapping("/checkout")
@RequiredArgsConstructor
@SaCheckPermission("checkout-checkoutmanage-cancheck")
public class CheckOutController {

    private final ReportService reportService;

    private final CheckoutService checkoutService;

    private final CashierJournalService cashierJournalService;

    private final CashierReportService cashierReportService;

    private final AssetsService assetsService;

    private final SalaryService salaryService;

    private final InvoiceService invoiceService;

    private final BalanceService balanceService;

    private final AccountSetsService accountSetsService;

    private final SubjectService subjectService;

    private final VoucherService voucherService;
    private final FinancialProcessService financialProcessService;

    @GetMapping
    public JsonResult list(@SessionAccountSet AccountSets accountSets,
                           @RequestParam(value = "accountSetsId", required = false) Long astId, @SessionUser UserVo userVo) {
        Long accountSetsId = accountSets.getId();
        if (astId != null) {
            if (userVo.getAccountSetsList().stream().noneMatch(act -> act.getId().equals(astId))) {
                return JsonResult.failure("无账套权限");
            }
            accountSetsId = astId;
        }
        return JsonResult.successful(checkoutService.list(accountSetsId));
    }

    /**
     * 最近一个未结账期间
     *
     * @param accountSets
     * @return
     */
    @GetMapping("last")
    public JsonResult last(@SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(checkoutService.last(accountSets));
    }

    /**
     * 期末检查
     *
     * @param year
     * @param month
     * @return
     */
    @GetMapping("finalCheck")
    public JsonResult finalCheck(@RequestParam Integer year, @RequestParam Integer month, @SessionAccountSet AccountSets accountSets) {
        LocalDate date = LocalDate.of(year, month, 1);
        TrialBalanceDto trialBalanceDto = balanceService.trialBalance(accountSets.getId(), date);

        return JsonResult.instance(trialBalanceDto.isFlat())
                .setData(trialBalanceDto);
    }

    /**
     * 报表检查
     *
     * @param year
     * @param month
     * @return
     */
    @GetMapping("reportCheck")
    public JsonResult reportCheck(@RequestParam Integer year, @RequestParam Integer month, @SessionUser UserVo userVo, @SessionAccountSet AccountSets accountSets) {
        Long accountSetsId = accountSets.getId();
        try {
            List<ReportItemsDto> items = reportService.items("zcfz", accountSetsId, false);
            List<ReportItemsDto> zcfz = reportService.reportData(items, userVo.getAccountSets(), "zcfz", userVo.getLocalCurrencyId(), LocalDate.of(year, month, 1), "TERM");
            Optional<ReportItemsDto> asset = zcfz.stream().filter(it -> "资产总计".equals(it.getTitle())).findFirst();
            if (asset.isPresent()) {
                Optional<ReportItemsDto> liability = zcfz.stream().filter(it -> "负债和所有者权益（或股东权益）总计".equals(it.getTitle())).findFirst();
                if (liability.isEmpty()) {
                    liability = zcfz.stream().filter(it -> "负债和净资产总计".equals(it.getTitle())).findFirst();
                }
                Optional<ReportItemsDto> ownerSEquity = zcfz.stream().filter(it -> "所有者权益（或股东权益）合计".equals(it.getTitle())).findFirst();
                if (ownerSEquity.isEmpty()) {
                    ownerSEquity = zcfz.stream().filter(it -> "净资产合计".equals(it.getTitle())).findFirst();
                }
                Optional<ReportItemsDto> totalLiabilities = zcfz.stream().filter(it -> "负债合计".equals(it.getTitle())).findFirst();

                if (liability.isPresent()) {
                    //资产
                    BigDecimal assetVal = NumberUtil.round(asset.get().getValues().get("期末余额"), 2);
                    //负债类
                    BigDecimal totalLiabilitiesVal = NumberUtil.round(totalLiabilities.get().getValues().get("期末余额"), 2);
                    //权益类
                    BigDecimal ownerSEquityVal = NumberUtil.round(ownerSEquity.get().getValues().get("期末余额"), 2);

                    boolean startEq = NumberUtil.equals(NumberUtil.round(asset.get().getValues().get("年初余额"), 2), NumberUtil.round(liability.get().getValues().get("年初余额"), 2));
                    boolean endEq = NumberUtil.equals(assetVal, NumberUtil.round(liability.get().getValues().get("期末余额"), 2));
                    BigDecimal rights = NumberUtil.round(NumberUtil.sub(assetVal, totalLiabilitiesVal), 2);
                    return JsonResult.successful(Dict.create()
                            .set("资产类", assetVal)
                            .set("负债类", totalLiabilitiesVal)
                            .set("权益类", ownerSEquityVal)
                            .set("rights", rights)
                            .set("equal", NumberUtil.equals(rights, ownerSEquityVal))
                            .set("sub", NumberUtil.sub(rights, ownerSEquityVal))
                            .set("startEq", startEq)
                            .set("endEq", endEq)
                            .set("result", NumberUtil.equals(NumberUtil.sub(rights, ownerSEquityVal), BigDecimal.ZERO)));
                }
            }
            return JsonResult.failure("资产负债表缺少行：【资产总计】 或 【负债和所有者权益（或股东权益）总计】，无法进行检查~");
        } catch (Exception e) {
            return JsonResult.failure(e.getMessage());
        }
    }

    /**
     * 断号检查
     *
     * @param year
     * @param month
     * @return
     */
    @GetMapping("brokenCheck")
    public JsonResult brokenCheck(@RequestParam Integer year, @RequestParam Integer month, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.instance(this.checkoutService.brokenCheck(accountSets.getId(), year, month));
    }

    /**
     * 审核检查
     *
     * @param year
     * @param month
     * @return
     */
    @GetMapping("reviewedCheck")
    public JsonResult reviewedCheck(@RequestParam Integer year, @RequestParam Integer month, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.instance(this.checkoutService.reviewedCheck(accountSets.getId(), year, month));
    }

    /**
     * 资金检查
     *
     * @param year
     * @param month
     * @return
     */
    @GetMapping("cashierCheck")
    public JsonResult cashierCheck(@RequestParam Integer year, @RequestParam Integer month, @SessionUser UserVo userVo, @SessionAccountSet AccountSets accountSets) {
        Long accountSetsId = accountSets.getId();
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        // FN-FIX-CASHIER-CHECK-LOGIC：与 openapi 共用同一 cashierPassed 单一真相源，资金检查 = 全部日记账已做凭证 且 期末账实相符。
        // 原逻辑极性反（`cashiered && (期初差异!=期末差异)`）且只在顶层找「差异」行（实际嵌 children）→ 账实相符账套被误判未过。
        return JsonResult.instance(cashierReportService.cashierPassed(accountSetsId, start, end, userVo.getLocalCurrencyId()));
    }

    /**
     * 资产检查
     *
     * @param year
     * @param month
     * @return
     */
    @GetMapping("assetsCheck")
    public JsonResult assetsCheck(@RequestParam Integer year, @RequestParam Integer month, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.instance(this.assetsService.assetsCheck(accountSets.getId(), year, month));
    }

    /**
     * 工资检查
     *
     * @param year
     * @param month
     * @return
     */
    @GetMapping("salaryCheck")
    public JsonResult salaryCheck(@RequestParam Integer year, @RequestParam Integer month, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.instance(this.salaryService.salaryCheck(accountSets.getId(), year, month));
    }

    /**
     * 发票检查
     *
     * @param year
     * @param month
     * @return
     */
    @GetMapping("invoiceCheck")
    public JsonResult invoiceCheck(@RequestParam Integer year, @RequestParam Integer month, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.instance(this.invoiceService.invoiceCheck(accountSets.getId(), year, month));
    }

    /**
     * 损益检查
     *
     * @param year
     * @param month
     * @return
     */
    @GetMapping("profitCheck")
    public JsonResult profitCheck(@RequestParam Integer year, @RequestParam Integer month, @SessionAccountSet AccountSets accountSets) {
        Long accountSetsId = accountSets.getId();
        List<Voucher> vouchers = voucherService.loadProfitCheck(accountSetsId, year, month);
        List<ProfitCheckDto> dicts = this.checkoutService.profitCheck(accountSetsId, year, month);
        if (CollUtil.isNotEmpty(dicts)) {
            CopyOnWriteArrayList<ProfitCheckDto> profitCheckDtos = CollUtil.newCopyOnWriteArrayList(dicts);
            ProfitCheckDto wfplr = null;
            Subject subject = subjectService.loadByName("本年利润", accountSetsId);
            if (subject == null) {
                subject = subjectService.loadByName("非限定性净资产", accountSetsId);
            } else {
                Subject beforeProfitAndLoss = subjectService.loadByName("以前年度损益调整", accountSetsId);

                // 以前年度损益调整
                if (beforeProfitAndLoss != null && dicts.stream().anyMatch(getProfitCheckDtoPredicate(beforeProfitAndLoss))) {
                    List<ProfitCheckDto> list = dicts.stream().filter(getProfitCheckDtoPredicate(beforeProfitAndLoss)).toList();
                    Subject undistributedProfit = subjectService.loadByName("未分配利润", accountSetsId);
                    dicts.removeIf(getProfitCheckDtoPredicate(beforeProfitAndLoss));
                    if (!undistributedProfit.getLeaf()) {
                        undistributedProfit = subjectService.loadFirstLeafChildren(undistributedProfit);
                    }
                    wfplr = getProfitCheckDto(undistributedProfit, list);
                }
            }

            if (!subject.getLeaf()) {
                subject = subjectService.loadFirstLeafChildren(subject);
            }

            ProfitCheckDto bnLr = getProfitCheckDto(subject, dicts);
            // 按借贷方向排序
            List<ProfitCheckDto> sortedItems = new ArrayList<>(profitCheckDtos.stream().filter(d -> d.getBalanceDirection().equals("贷")).toList());
            sortedItems.addAll(profitCheckDtos.stream().filter(d -> d.getBalanceDirection().equals("借")).toList());
            BigDecimal balance = bnLr.getBalance();
            // 如果利润不等于0，插入
            if (!balance.equals(BigDecimal.ZERO)) {
                sortedItems.add(0, bnLr);
            }

            // 如果有未分配利润，插入
            if (wfplr != null) {
                sortedItems.add(1, wfplr);
            }

            return JsonResult.successful()
                    .data("balance", sortedItems.stream().filter(d -> "借".equalsIgnoreCase(d.getBalanceDirection()))
                            .map(ProfitCheckDto::getBalance).reduce(BigDecimal::add).orElse(BigDecimal.ZERO))
                    .data("voucherItems", sortedItems);
        } else {
            checkoutService.updateStatus(accountSetsId, year, month, 1);
            return JsonResult.successful()
                    .data("voucherIds", vouchers.stream().map(Voucher::getId).toList())
                    .data("creditAmount", vouchers.stream().map(Voucher::getCreditAmount).reduce(BigDecimal::add).orElse(BigDecimal.ZERO));
        }
    }

    private ProfitCheckDto getProfitCheckDto(Subject subject, List<ProfitCheckDto> dicts) {
        ProfitCheckDto bnLr = new ProfitCheckDto();
        bnLr.setBalance(BigDecimal.ZERO);
        bnLr.setFcAmount(BigDecimal.ZERO);
        bnLr.setSubjectId(subject.getId());
        bnLr.setCode(subject.getCode());
        bnLr.setSubject(BeanUtil.toBean(subject, SubjectDto.class));
        bnLr.setFullName(subject.getFullName());
        bnLr.setBalanceDirection(subject.getBalanceDirection());
        BigDecimal jie = dicts.stream().filter(d -> "借".equalsIgnoreCase(d.getBalanceDirection()))
                .map(ProfitCheckDto::getBalance).reduce(BigDecimal::add).orElse(BigDecimal.ZERO);
        BigDecimal dai = dicts.stream().filter(d -> "贷".equalsIgnoreCase(d.getBalanceDirection()))
                .map(ProfitCheckDto::getBalance).reduce(BigDecimal::add).orElse(BigDecimal.ZERO);
        BigDecimal lr = NumberUtil.sub(dai, jie);
        if (lr.compareTo(BigDecimal.ZERO) > 0) {
            bnLr.setBalance(lr);
            bnLr.setBalanceDirection("借");
        } else {
            bnLr.setBalance(lr.abs());
            bnLr.setBalanceDirection("贷");
        }
        return bnLr;
    }

    private Predicate<ProfitCheckDto> getProfitCheckDtoPredicate(Subject beforeProfitAndLoss) {
        return pc -> pc.getCode().startsWith(beforeProfitAndLoss.getCode());
    }

    /**
     * 结账
     *
     * @param year
     * @param month
     * @return
     */
    @GetMapping("invoicing")
    public JsonResult invoicing(@RequestParam Integer year, @RequestParam Integer month, @SessionUser UserVo userVo) {
        AccountSets accountSets = userVo.getAccountSets();
        this.checkoutService.invoicing(accountSets, year, month);
        accountSets.setCurrentAccountDate(LocalDate.of(year, month, 1).plusMonths(1));
        accountSetsService.update(accountSets);
        LocalDate date = LocalDate.of(year, month, 1);
        FinancialProcessDTO financialProcessDTO = new FinancialProcessDTO();
        financialProcessDTO.setAccountingPeriod(String.valueOf(date));
        financialProcessDTO.setAccountSetsId(accountSets.getId());
        financialProcessDTO.setIsAccountingDone("是");
        financialProcessService.updateFinancialProcess(financialProcessDTO);
        StpUtil.getSession().set(S_USER_KEY, userVo);
        return JsonResult.successful();
    }

    /**
     * 反结账
     *
     * @param year
     * @param month
     * @return
     */
    @GetMapping("unCheck")
    public JsonResult unCheck(@RequestParam Integer year, @RequestParam Integer month, @SessionUser UserVo userVo) {
        if (!this.checkoutService.unCheck(userVo.getAccountSets(), year, month)) {
            return JsonResult.failure();
        }
        AccountSets accountSets = userVo.getAccountSets();
        accountSets.setCurrentAccountDate(LocalDate.of(year, month, 1));
        accountSetsService.update(accountSets);
        LocalDate date = LocalDate.of(year, month, 1);
        FinancialProcessDTO financialProcessDTO = new FinancialProcessDTO();
        financialProcessDTO.setAccountingPeriod(String.valueOf(date));
        financialProcessDTO.setAccountSetsId(accountSets.getId());
        financialProcessDTO.setIsAccountingDone("否");
        financialProcessService.updateFinancialProcess(financialProcessDTO);
        StpUtil.getSession().set(S_USER_KEY, userVo);
        return JsonResult.successful();
    }

    /**
     * 检查启用期间是否已结账
     *
     * @return
     */
    @GetMapping("begin/check")
    public JsonResult beginCheck(@SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(checkoutService.beginCheck(accountSets));
    }

    /**
     * 结转凭证
     *
     * @return
     */
    @GetMapping("vouchers")
    public JsonResult vouchers(@SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(checkoutService.beginCheck(accountSets));
    }

    /**
     * 查询账期下的是否有银行流水
     */
    @SaIgnore
    @GetMapping("/selectBankStatement")
    public JsonResult selectBankStatement( @SessionAccountSet AccountSets accountSets,String currentAccountDate,String type) {
         Long accountSetId = accountSets.getId();
        return JsonResult.successful(checkoutService.selectBankStatement(accountSetId,currentAccountDate,type));
    }
}
