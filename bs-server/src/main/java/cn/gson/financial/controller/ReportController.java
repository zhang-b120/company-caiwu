package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.dto.CurrencyDto;
import cn.gson.financial.kernel.model.dto.ReportItemsDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.ReportCashFlowDraft;
import cn.gson.financial.kernel.model.entity.ReportItems;
import cn.gson.financial.kernel.model.vo.DownLoadReportVo;
import cn.gson.financial.kernel.model.vo.FeeDetailQuery;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.BalanceCalcService;
import cn.gson.financial.kernel.service.CheckoutService;
import cn.gson.financial.kernel.service.ReportCashFlowDraftService;
import cn.gson.financial.kernel.service.ReportService;
import cn.gson.financial.rpa.CheckData;
import cn.hutool.core.lang.Assert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

/**
 *
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年11月17日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("report")
public class ReportController {

    private final ReportService reportService;

    private final CheckoutService checkoutService;

    private final ReportCashFlowDraftService cashFlowDraftService;

    private final BalanceCalcService balanceCalcService;

    private final CheckData c;
    private final CheckData checkData;

    // 添加AccountSetsService依赖
    private final cn.gson.financial.kernel.service.AccountSetsService accountSetsService;

    // 添加CurrencyService依赖
    private final cn.gson.financial.kernel.service.CurrencyService currencyService;

    @GetMapping
    public JsonResult list(@SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(reportService.list(accountSets.getId()));
    }

    @GetMapping("select")
    public JsonResult selectList(@SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(reportService.selectList(accountSets.getId()));
    }

    /**
     * 费用明细
     *
     * @param accountSets
     * @return
     */
    @PostMapping("fee/detail")
    public JsonResult feeDetail(@SessionAccountSet AccountSets accountSets, @RequestBody FeeDetailQuery query) {
        return JsonResult.successful(reportService.feeDetail(accountSets, query));
    }

    @GetMapping("{reportKey}")
    @SaCheckPermission({"report-incomestatement-canview", "report-balancesheet-canview", "report-cashflowstatement-canview"})
    public JsonResult load(@SessionAccountSet AccountSets accountSets, @PathVariable String reportKey) {
        return JsonResult.successful(reportService.load(reportKey, accountSets.getId()));
    }

    /**
     * 获取重分类状态
     * @param accountSets
     * @return
     */
    @GetMapping("/getResetStatus")
    public JsonResult getResetStatus(@SessionAccountSet AccountSets accountSets) {
            return JsonResult.successful(reportService.getResetStatus(accountSets.getId()));
    }
    @GetMapping("/setResetStatus")
    @SaCheckPermission("report-balancesheet-canview")
    public JsonResult getResetStatus(@SessionAccountSet AccountSets accountSets, Boolean reset) {
        reportService.setResetStatus(accountSets.getId(),reset);
        return JsonResult.successful();
    }
    /**
     * 保存报表列期初金额，只有现金流期初使用
     *
     * @param accountSets
     * @return
     */
    @PutMapping("items/init")
    @SaCheckPermission({"report-incomestatement-canview", "report-balancesheet-canview", "report-cashflowstatement-canview"})
    public JsonResult saveInitColData(@SessionAccountSet AccountSets accountSets, @RequestBody List<ReportItems> reportItems) {
        Assert.isFalse(checkoutService.beginCheck(accountSets), "初始期间已结账，不允许变更");
        reportService.saveInitColData(accountSets.getId(), reportItems);
        return JsonResult.successful();
    }

    /**
     * 更新公式
     *
     * @param
     * @param reportItems
     * @return
     */
    @PutMapping("items/formula")
    @SaCheckPermission({"report-incomestatement-canedit", "report-balancesheet-canedit", "report-cashflowstatement-canedit"})
    public JsonResult updateItemsFormula(@SessionAccountSet AccountSets accountSets, @RequestBody ReportItems reportItems) {
        reportItems.setAccountSetsId(accountSets.getId());
        reportService.saveItemsFormula(reportItems);
        return JsonResult.successful();
    }

    /**
     * 加载报表数据
     *
     * @param templateKey
     * @param userVo
     * @param voucherDate
     * @param reset       是否重分类
     * @param dimension   维度，季度为QUARTER
     * @return
     */
    @GetMapping("data/{templateKey}")
    @SaCheckPermission({"report-incomestatement-canview", "report-balancesheet-canview", "report-cashflowstatement-canview"})
    public JsonResult loadData(@PathVariable String templateKey,
                               @SessionUser UserVo userVo,
                               @SessionAccountSet AccountSets accountSets,
                               LocalDate voucherDate,
                               @RequestParam(defaultValue = "0") Boolean reset,
                               String dimension) {
        Long accountSetId = accountSets.getId();
        List<ReportItemsDto> items = reportService.items(templateKey, accountSetId, reset);
        try {
            List<ReportItemsDto> result = reportService.reportData(items, userVo.getAccountSets(), templateKey, userVo.getLocalCurrencyId(), voucherDate, dimension);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("报表计算错误~", e);
            return JsonResult.successful(items).setMsg(e.getMessage());
        }
    }
    @GetMapping("checkZcfz")
    public void checkZcfz(@SessionUser UserVo userVo) {
        checkData.checkZcfz(userVo,"zcfz", userVo.getLocalCurrencyId(), false, "TERM");
    }

    /**
     * 期间现金流底稿
     * @param
     * @param period
     * @return
     */
    @GetMapping("cash/flow/draft")
    @SaCheckPermission({"report-cashflowstatement-canedit"})
    public JsonResult loadCashFlowDraft(@SessionAccountSet AccountSets accountSets, LocalDate period) {
        return JsonResult.successful(cashFlowDraftService.list(accountSets.getId(), period));
    }

    /**
     * 保存期间现金流底稿
     * @param accountSets
     * @param period
     * @return
     */
    @PostMapping("cash/flow/draft")
    @SaCheckPermission({"report-cashflowstatement-canedit"})
    public JsonResult saveCashFlowDraft(@SessionAccountSet AccountSets accountSets,
                                        @SessionUser UserVo user,
                                        LocalDate period,
                                        @RequestBody List<ReportCashFlowDraft> reportCashFlowDrafts) {
        Long accountSetsId = accountSets.getId();
        if (user.getCheckoutList().stream().anyMatch(it -> it.getCheckDate().isEqual(period) && it.getStatus() == 2)) {
            return JsonResult.failure("期间已结转~");
        }
        cashFlowDraftService.save(accountSetsId, period, reportCashFlowDrafts);
        balanceCalcService.calcCashFlow(accountSetsId, YearMonth.from(period));
        return JsonResult.successful();
    }

    /**
     * 重置期间现金流底稿
     * @param accountSets
     * @param user
     * @param period
     * @return
     */
    @DeleteMapping("cash/flow/draft")
    @SaCheckPermission({"report-cashflowstatement-canedit"})
    public JsonResult deleteCashFlowDraft(@SessionAccountSet AccountSets accountSets,
                                          @SessionUser UserVo user,
                                          LocalDate period) {
        Long accountSetsId = accountSets.getId();
        if (user.getCheckoutList().stream().anyMatch(it -> it.getCheckDate().isEqual(period) && it.getStatus() == 2)) {
            return JsonResult.failure("期间已结转~");
        }
        cashFlowDraftService.delete(accountSetsId, period);
        balanceCalcService.calcCashFlow(accountSetsId, YearMonth.from(period));
        return JsonResult.successful();
    }

    /**
     * 导出财务报表Excel - 使用流式传输（外部接口）
     *
     * @param templateKey   报表模板标识
     * @param accountSetId  账套ID
     * @param voucherDate   凭证日期
     * @param reset         是否重分类
     * @param dimension     维度，季度为QUARTER
     * @param companyName   公司名称（可选，用于Excel显示）
     * @param currencyId    币别ID（可选，默认使用本位币）
     */
    @GetMapping("export/{templateKey}")
    @SaIgnore
    public ResponseEntity<StreamingResponseBody> exportReport(@PathVariable String templateKey,
                                                             @RequestParam Long accountSetId,
                                                             @RequestParam LocalDate voucherDate,
                                                             @RequestParam(defaultValue = "false") Boolean reset,
                                                             @RequestParam(defaultValue = "TERM") String dimension,
                                                             @RequestParam(required = false) String companyName,
                                                             @RequestParam(required = false) Long currencyId) throws IOException {

        // 获取账套信息
        AccountSets accountSets = accountSetsService.getById(accountSetId);
        if (accountSets == null) {
            throw new IllegalArgumentException("账套不存在，ID: " + accountSetId);
        }
        // 如果提供了公司名称，则使用提供的名称，否则使用账套中的名称
        if (companyName != null && !companyName.trim().isEmpty()) {
            accountSets.setCompanyName(companyName);
        }
        // 确定使用的币别ID，如果未提供则使用账套的本位币
        Long finalCurrencyId = currencyId != null ? currencyId : reportService.getLocalCurrencyId(accountSetId);
        List<ReportItemsDto> items = reportService.items(templateKey, accountSetId, reset);
        List<ReportItemsDto> result = reportService.reportData(items, accountSets, templateKey, finalCurrencyId, voucherDate, dimension);
        // 创建文件名
        String reportName = reportService.getReportName(templateKey);
        String fileName = reportName + "_" + voucherDate.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".xlsx";
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8);

        // 创建流式响应体
        StreamingResponseBody stream = outputStream -> {
            try {
                reportService.exportToExcelStream(result, accountSets, templateKey, voucherDate, dimension, outputStream);
            } catch (Exception e) {
                log.error("导出报表失败", e);
                throw new RuntimeException("导出报表失败: " + e.getMessage(), e);
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedFileName + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(stream);
    }

    /**
     * 报表下载
     * @param downLoadReportVo
     */
    @PostMapping("/downLoadReport")
    public void downLoadReport(@RequestBody DownLoadReportVo downLoadReportVo) {
        reportService.downLoadReport(downLoadReportVo);
    }
}
