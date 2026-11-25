package cn.gson.financial.controller;

import cn.gson.financial.common.PdfFontUtils;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.vo.AccountSummaryItemVo;
import cn.gson.financial.kernel.service.CashierReportService;
import cn.gson.financial.kernel.tool.JxlsUtils;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.NumberUtil;
import com.itextpdf.text.Document;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Rectangle;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 *
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年11月10日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/cashier/report")
public class CashierReportController {

    private final CashierReportService cashierReportService;

    @GetMapping("account/summary")
    public Object accountSummary(LocalDate start, LocalDate end,
                                 @SessionAccountSet AccountSets accountSets,
                                 @RequestParam(defaultValue = "false") Boolean download,
                                 @RequestParam(defaultValue = "xlsx") String type,
                                 HttpServletResponse response) {
        List<AccountSummaryItemVo> accountSummaryItemVos = cashierReportService.accountSummary(start, end, accountSets.getId());
        if (download) {
            try {
                Map<String, List<AccountSummaryItemVo>> collect =
                        accountSummaryItemVos.stream().collect(Collectors.groupingBy(AccountSummaryItemVo::getType));

                List<AccountSummaryItemVo> cash = collect.getOrDefault("现金", new ArrayList<>());
                List<AccountSummaryItemVo> bankDeposits = collect.getOrDefault("银行存款", new ArrayList<>());

                if ("xlsx".equals(type)) {
                    JxlsUtils.exportExcel("template/account_summary.xlsx", response.getOutputStream(), Dict.create()
                            .set("astName", accountSets.getCompanyName())
                            .set("dateS", start.format(DateTimeFormatter.ofPattern("yyyy年MM期")))
                            .set("dateE", end.format(DateTimeFormatter.ofPattern("yyyy年MM期")))
                            .set("cashList", cash)
                            .set("bankDeposits", bankDeposits));
                } else if ("pdf".equals(type)) {
                    Rectangle rect = new Rectangle(PageSize.A4);
                    Document document = new Document(rect);
                    document.addTitle("账户资金报表");
                    ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
                    PdfWriter writer = PdfWriter.getInstance(document, byteOutputStream);
                    writer.setPdfVersion(PdfWriter.PDF_VERSION_1_7);
                    document.open();
                    response.setContentType("application/pdf");
                    document.add(PdfFontUtils.getFont(1, "账户资金报表", 1));
                    PdfPTable table = new PdfPTable(new float[]{1, 1, 1});
                    table.setWidthPercentage(100);
                    PdfFontUtils.PdfPCellBuilder.builder("编制单位：" + accountSets.getCompanyName())
                            .noBorder().addCell(table);
                    PdfFontUtils.PdfPCellBuilder.builder(start.format(DateTimeFormatter.ofPattern("yyyy年MM期")) + "至" + end.format(DateTimeFormatter.ofPattern("yyyy年MM期")))
                            .noBorder().horizontalCenterAlignment().addCell(table);
                    PdfFontUtils.PdfPCellBuilder.builder("单位：元")
                            .horizontalRightAlignment().noBorder().addCell(table);
                    document.add(table);
                    float[] dataCols = new float[8];
                    Arrays.fill(dataCols, 1);
                    PdfPTable dataTable = new PdfPTable(dataCols);
                    dataTable.setWidthPercentage(100);
                    String[] columnTitles = {"编码", "账户名称", "期初余额", "收入总额", "支出总额", "余额", "收入笔数", "支出笔数"};
                    for (String columnTitle : columnTitles) {
                        PdfFontUtils.PdfPCellBuilder.builder(columnTitle)
                                .horizontalCenterAlignment().addCell(dataTable);
                    }

                    for (AccountSummaryItemVo vo : cash) {
                        PdfFontUtils.PdfPCellBuilder.builder(vo.getCode()).addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(vo.getName()).addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(vo.getInitialBalance()))
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(vo.getSr()))
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(vo.getZc()))
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(vo.getBalance()))
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(vo.getSrbs() + "")
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(vo.getZcbs() + "")
                                .horizontalRightAlignment().addCell(dataTable);
                    }

                    Double xjInitialBalance = cash.stream().map(AccountSummaryItemVo::getInitialBalance).reduce(Double::sum).orElse(0D);
                    Double xjSr = cash.stream().map(AccountSummaryItemVo::getSr).reduce(Double::sum).orElse(0D);
                    Double xjZc = cash.stream().map(AccountSummaryItemVo::getZc).reduce(Double::sum).orElse(0D);
                    Double xjBalance = cash.stream().map(AccountSummaryItemVo::getBalance).reduce(Double::sum).orElse(0D);
                    Integer xjSrbs = cash.stream().map(AccountSummaryItemVo::getSrbs).reduce(Integer::sum).orElse(0);
                    Integer xjZCbs = cash.stream().map(AccountSummaryItemVo::getZcbs).reduce(Integer::sum).orElse(0);

                    PdfFontUtils.PdfPCellBuilder.builder("").addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder("现金合计").addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(xjInitialBalance))
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(xjSr))
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(xjZc))
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(xjBalance))
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(xjSrbs + "")
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(xjZCbs + "")
                            .horizontalRightAlignment().addCell(dataTable);

                    for (AccountSummaryItemVo vo : bankDeposits) {
                        PdfFontUtils.PdfPCellBuilder.builder(vo.getCode()).addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(vo.getName()).addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(vo.getInitialBalance()))
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(vo.getSr()))
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(vo.getZc()))
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(vo.getBalance()))
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(vo.getSrbs() + "")
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(vo.getZcbs() + "")
                                .horizontalRightAlignment().addCell(dataTable);
                    }

                    Double yhInitialBalance = bankDeposits.stream().map(AccountSummaryItemVo::getInitialBalance).reduce(Double::sum).orElse(0D);
                    Double yhSr = bankDeposits.stream().map(AccountSummaryItemVo::getSr).reduce(Double::sum).orElse(0D);
                    Double yhZc = bankDeposits.stream().map(AccountSummaryItemVo::getZc).reduce(Double::sum).orElse(0D);
                    Double yhBalance = bankDeposits.stream().map(AccountSummaryItemVo::getBalance).reduce(Double::sum).orElse(0D);
                    Integer yhSrbs = bankDeposits.stream().map(AccountSummaryItemVo::getSrbs).reduce(Integer::sum).orElse(0);
                    Integer yhZCbs = bankDeposits.stream().map(AccountSummaryItemVo::getZcbs).reduce(Integer::sum).orElse(0);

                    PdfFontUtils.PdfPCellBuilder.builder("").addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder("银行合计").addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(yhInitialBalance))
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(yhSr))
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(yhZc))
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(yhBalance))
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(yhSrbs + "")
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(yhZCbs + "")
                            .horizontalRightAlignment().addCell(dataTable);

                    PdfFontUtils.PdfPCellBuilder.builder("").addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder("总计").addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(xjInitialBalance + yhInitialBalance))
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(yhSr + xjSr))
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(yhZc + xjZc))
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(yhBalance + xjBalance))
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder((yhSrbs + xjSrbs) + "")
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder((yhZCbs + xjZCbs) + "")
                            .horizontalRightAlignment().addCell(dataTable);

                    document.add(dataTable);
                    document.close();
                    HttpHeaders httpHeaders = new HttpHeaders();
                    String fileName = "现金记账列表.pdf";
                    httpHeaders.setContentType(MediaType.APPLICATION_PDF);
                    httpHeaders.set("X-UA-Compatible", "IE=Edge");
                    httpHeaders.set(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8));
                    return new ResponseEntity<>(byteOutputStream.toByteArray(),
                            httpHeaders,
                            HttpStatus.OK);
                }
            } catch (Exception e) {
                log.error("资金报表按账户汇总导出异常", e);
                return JsonResult.failure(e.getMessage());
            }
            return null;
        } else {
            return JsonResult.successful(accountSummaryItemVos);
        }
    }

    @GetMapping("account/check")
    public JsonResult accountCheck(LocalDate start, LocalDate end, Long cashierAccountId, Long currencyId, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(cashierReportService.accountCheck(start, end, cashierAccountId, currencyId, accountSets.getId()));
    }

    @GetMapping("io/summary")
    public Object ioTypeSummary(LocalDate start, LocalDate end,
                                @SessionAccountSet AccountSets accountSets,
                                @RequestParam(defaultValue = "false") Boolean download,
                                @RequestParam(defaultValue = "xlsx") String type,
                                HttpServletResponse response) {
        List<AccountSummaryItemVo> accountSummaryItemVos = cashierReportService.ioTypeSummary(start, end, accountSets.getId());
        if (download) {
            try {
                Map<String, List<AccountSummaryItemVo>> collect =
                        accountSummaryItemVos.stream().collect(Collectors.groupingBy(AccountSummaryItemVo::getType));

                List<AccountSummaryItemVo> cash = collect.getOrDefault("收", new ArrayList<>());
                List<AccountSummaryItemVo> bankDeposits = collect.getOrDefault("支", new ArrayList<>());

                if ("xlsx".equals(type)) {
                    JxlsUtils.exportExcel("template/io_summary.xlsx", response.getOutputStream(), Dict.create()
                            .set("astName", accountSets.getCompanyName())
                            .set("dateS", start.format(DateTimeFormatter.ofPattern("yyyy年MM期")))
                            .set("dateE", end.format(DateTimeFormatter.ofPattern("yyyy年MM期")))
                            .set("cashList", cash)
                            .set("bankDeposits", bankDeposits));
                } else if ("pdf".equals(type)) {
                    Rectangle rect = new Rectangle(PageSize.A4);
                    Document document = new Document(rect);
                    document.addTitle("账户资金报表");
                    ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
                    PdfWriter writer = PdfWriter.getInstance(document, byteOutputStream);
                    writer.setPdfVersion(PdfWriter.PDF_VERSION_1_7);
                    document.open();
                    response.setContentType("application/pdf");
                    document.add(PdfFontUtils.getFont(1, "账户资金报表", 1));
                    PdfPTable table = new PdfPTable(new float[]{1, 1, 1});
                    table.setWidthPercentage(100);
                    PdfFontUtils.PdfPCellBuilder.builder("编制单位：" + accountSets.getCompanyName())
                            .noBorder().addCell(table);
                    PdfFontUtils.PdfPCellBuilder.builder(start.format(DateTimeFormatter.ofPattern("yyyy年MM期")) + "至" + end.format(DateTimeFormatter.ofPattern("yyyy年MM期")))
                            .noBorder().horizontalCenterAlignment().addCell(table);
                    PdfFontUtils.PdfPCellBuilder.builder("单位：元")
                            .horizontalRightAlignment().noBorder().addCell(table);
                    document.add(table);
                    float[] dataCols = new float[6];
                    Arrays.fill(dataCols, 1);
                    PdfPTable dataTable = new PdfPTable(dataCols);
                    dataTable.setWidthPercentage(100);
                    String[] columnTitles = {"编码", "收支类别", "收入总额", "支出总额", "收入笔数", "支出笔数"};
                    for (String columnTitle : columnTitles) {
                        PdfFontUtils.PdfPCellBuilder.builder(columnTitle)
                                .horizontalCenterAlignment().addCell(dataTable);
                    }

                    for (AccountSummaryItemVo vo : cash) {
                        PdfFontUtils.PdfPCellBuilder.builder(vo.getCode()).addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(vo.getName()).addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(vo.getSr()))
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(vo.getZc()))
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(vo.getSrbs() + "")
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(vo.getZcbs() + "")
                                .horizontalRightAlignment().addCell(dataTable);
                    }

                    Double xjSr = cash.stream().map(AccountSummaryItemVo::getSr).reduce(Double::sum).orElse(0D);
                    Double xjZc = cash.stream().map(AccountSummaryItemVo::getZc).reduce(Double::sum).orElse(0D);
                    Integer xjSrbs = cash.stream().map(AccountSummaryItemVo::getSrbs).reduce(Integer::sum).orElse(0);
                    Integer xjZCbs = cash.stream().map(AccountSummaryItemVo::getZcbs).reduce(Integer::sum).orElse(0);

                    PdfFontUtils.PdfPCellBuilder.builder("").addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder("收入合计").addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(xjSr))
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(xjZc))
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(xjSrbs + "")
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(xjZCbs + "")
                            .horizontalRightAlignment().addCell(dataTable);

                    for (AccountSummaryItemVo vo : bankDeposits) {
                        PdfFontUtils.PdfPCellBuilder.builder(vo.getCode()).addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(vo.getName()).addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(vo.getSr()))
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(vo.getZc()))
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(vo.getSrbs() + "")
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfFontUtils.PdfPCellBuilder.builder(vo.getZcbs() + "")
                                .horizontalRightAlignment().addCell(dataTable);
                    }

                    Double yhSr = bankDeposits.stream().map(AccountSummaryItemVo::getSr).reduce(Double::sum).orElse(0D);
                    Double yhZc = bankDeposits.stream().map(AccountSummaryItemVo::getZc).reduce(Double::sum).orElse(0D);
                    Integer yhSrbs = bankDeposits.stream().map(AccountSummaryItemVo::getSrbs).reduce(Integer::sum).orElse(0);
                    Integer yhZCbs = bankDeposits.stream().map(AccountSummaryItemVo::getZcbs).reduce(Integer::sum).orElse(0);

                    PdfFontUtils.PdfPCellBuilder.builder("").addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder("支出合计").addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(yhSr))
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(yhZc))
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(yhSrbs + "")
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(yhZCbs + "")
                            .horizontalRightAlignment().addCell(dataTable);

                    PdfFontUtils.PdfPCellBuilder.builder("").addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder("总计").addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(yhSr + xjSr))
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(yhZc + xjZc))
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder((yhSrbs + xjSrbs) + "")
                            .horizontalRightAlignment().addCell(dataTable);
                    PdfFontUtils.PdfPCellBuilder.builder((yhZCbs + xjZCbs) + "")
                            .horizontalRightAlignment().addCell(dataTable);

                    document.add(dataTable);
                    document.close();
                    HttpHeaders httpHeaders = new HttpHeaders();
                    String fileName = "现金记账列表.pdf";
                    httpHeaders.setContentType(MediaType.APPLICATION_PDF);
                    httpHeaders.set("X-UA-Compatible", "IE=Edge");
                    httpHeaders.set(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8));
                    return new ResponseEntity<>(byteOutputStream.toByteArray(),
                            httpHeaders,
                            HttpStatus.OK);
                }
            } catch (Exception e) {
                log.error("资金报表按收支类别汇总导出异常", e);
                return JsonResult.failure(e.getMessage());
            }
            return null;
        } else {
            return JsonResult.successful(accountSummaryItemVos);
        }
    }

}
