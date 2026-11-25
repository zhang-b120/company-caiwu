package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.gson.financial.common.PdfFontUtils;
import cn.gson.financial.common.PdfFontUtils.PdfPCellBuilder;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.dto.AbDetailDto;
import cn.gson.financial.kernel.model.dto.CurrencyDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.vo.*;
import cn.gson.financial.kernel.service.AccountBookService;
import cn.gson.financial.kernel.service.CurrencyService;
import cn.gson.financial.kernel.service.SubjectService;
import cn.gson.financial.kernel.tool.JxlsUtils;
import cn.gson.financial.rpa.CheckData;
import cn.hutool.core.collection.CollUtil;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import reactor.util.function.Tuple3;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年09月07日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@RestController
@RequestMapping("/accountBook")
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AccountBookController {

    private final AccountBookService accountBookService;

    private final CurrencyService currencyService;

    private final SubjectService subjectService;
    private final CheckData checkData;

    @RequestMapping("subject")
    public JsonResult subjectList(@SessionAccountSet AccountSets accountSets, AccountBookQueryVO accountBookQueryVO) {
        accountBookQueryVO.setAccountSetsId(accountSets.getId());
        return JsonResult.successful(accountBookService.subjectList(accountBookQueryVO));
    }

    @RequestMapping("auxiliary")
    public JsonResult auxiliaryList(@SessionAccountSet AccountSets accountSets, AccountBookQueryVO accountBookQueryVO) {
        accountBookQueryVO.setAccountSetsId(accountSets.getId());
        return JsonResult.successful(accountBookService.auxiliaryList(accountBookQueryVO));
    }

    /**
     * 科目余额表
     *
     * @return
     */
    @RequestMapping("subjectBalance")
    @SaCheckPermission("accountbook-trialbalance-canview")
    public JsonResult subjectBalance(AccountBookQueryVO queryVO, @SessionAccountSet AccountSets accountSets) {
        queryVO.setAccountSetsId(accountSets.getId());
        return JsonResult.successful(accountBookService.subjectBalance(queryVO));
    }
    /*
    检索所有末期科目余额表
    * */
    @RequestMapping("lastkemuyue")
    public JsonResult subjectBalance(@SessionUser UserVo userVo) {
        checkData.checkkmye(userVo);
        return JsonResult.successful();
    }
    /**
     * 科目汇总
     *
     * @param queryVO
     * @return
     */
    @RequestMapping("subjectSummary")
    @SaCheckPermission("accountbook-categorizedaccountssummary-canview")
    public JsonResult subjectSummary(AccountBookQueryVO queryVO, @SessionAccountSet AccountSets accountSets) {
        queryVO.setAccountSetsId(accountSets.getId());
        return JsonResult.successful(accountBookService.subjectSummary(queryVO));
    }

    /**
     * 明细账
     *
     * @param queryVO
     * @param accountSets
     * @return
     */
    @GetMapping("details")
    @SaCheckPermission("accountbook-subsidiaryledger-canview")
    public JsonResult accountBookDetails(AccountBookQueryVO queryVO, @SessionAccountSet AccountSets accountSets, @SessionUser UserVo userVo) {
        queryVO.setAccountSetsId(accountSets.getId());
        queryVO.setLocalCurrencyId(userVo.getLocalCurrencyId());
        // 币别返回null时，查询所有
        if (queryVO.getCurrencyId() == null) {
            Subject subject = subjectService.getById(new PrimaryKey(queryVO.getSubjectId(), accountSets.getId()));
            // 判断当前查询科目是否启用多币别
            if (CollUtil.isNotEmpty(subject.getCurrencyAccounting())) {
                var detailsAll = new ArrayList<AbDetailDto>();
                // 按币别查询明细
                for (CurrencyDto dto : currencyService.list(accountSets.getId()).stream()
                        .filter(c -> subject.getCurrencyAccounting().contains(c.getId())).toList()) {
                    queryVO.setCurrencyId(dto.getId());
                    List<AbDetailDto> details = accountBookService.details(queryVO, accountSets.getEnableDate());
                    detailsAll.addAll(details);
                }
                return JsonResult.successful(detailsAll);
            }
            // 没有多币别查本币
            queryVO.setCurrencyId(userVo.getLocalCurrencyId());
        }
        return JsonResult.successful(accountBookService.details(queryVO, accountSets.getEnableDate()));
    }

    /**
     * 所有科目明细账
     *
     * @param queryVO
     * @param accountSets
     * @return
     */
    @PostMapping("details/all")
    @SaCheckPermission("accountbook-subsidiaryledger-canview")
    public JsonResult accountBookAllDetails(@RequestBody AccountBookQueryVO queryVO, @SessionAccountSet AccountSets accountSets, @SessionUser UserVo userVo) {
        queryVO.setAccountSetsId(accountSets.getId());
        queryVO.setCurrencyId(userVo.getLocalCurrencyId());
        Map<Long, List<AbDetailDto>> dataMap = new HashMap<>();
        for (Long subjectId : queryVO.getSubjectIds()) {
            queryVO.setSubjectId(subjectId);
            dataMap.put(subjectId, accountBookService.details(queryVO, accountSets.getEnableDate()));
        }
        return JsonResult.successful(dataMap);
    }

    /**
     * 总账
     *
     * @param queryVO
     * @return
     */
    @GetMapping("generalLedger")
    @SaCheckPermission("accountbook-generalledger-canview")
    public JsonResult generalLedger(AccountBookQueryVO queryVO, @SessionAccountSet AccountSets accountSets) {
        queryVO.setAccountSetsId(accountSets.getId());
        if (queryVO.getShowNumPrice()) {
            return JsonResult.successful(accountBookService.generalLedgerNum(queryVO));
        } else {
            return JsonResult.successful(accountBookService.generalLedger(queryVO));
        }
    }

    /**
     * 辅助明细账
     *
     * @param queryVO
     * @return
     */
    @GetMapping("auxiliaryDetails")
    @SaCheckPermission("accountbook-aasubsidiaryledger-canview")
    public JsonResult auxiliaryDetails(AccountBookQueryVO queryVO, @SessionAccountSet AccountSets accountSets) {
        queryVO.setAccountSetsId(accountSets.getId());
        return JsonResult.successful(accountBookService.auxiliaryDetails(queryVO, accountSets.getEnableDate()));
    }

    /**
     * 核算项目余额
     *
     * @param queryVO
     * @param accountSets
     * @return
     */
    @PostMapping("auxiliaryBalance")
    @SaCheckPermission("accountbook-aatrialbalance-canview")
    public JsonResult auxiliaryBalance(@RequestBody AccountBookQueryVO queryVO, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(accountBookService.auxiliaryBalance(queryVO, accountSets));
    }

    /**
     * 时序账
     *
     * @return
     */
    @GetMapping("timeSeries")
    @SaCheckPermission("accountbook-journal-canview")
    public JsonResult timeSeries(Page page, TimeSeriesQueryVo queryVo, @SessionAccountSet AccountSets accountSets) {
        queryVo.setAccountSetsId(accountSets.getId());
        return JsonResult.successful(accountBookService.timeSeries(page, queryVo));
    }

    /**
     * 多栏账
     *
     * @return
     */
    @GetMapping("multiColumnLedger")
    @SaCheckPermission("accountbook-journal-canview")
    public Object multiColumnLedger(MultiColumnLedgerQueryVo queryVo,
                                    @RequestParam(defaultValue = "false") Boolean download,
                                    @RequestParam(defaultValue = "xlsx") String type,
                                    @SessionUser UserVo userVo,
                                    @SessionAccountSet AccountSets accountSets,
                                    HttpServletResponse response) {
        queryVo.setAccountSetsId(accountSets.getId());
        queryVo.setLocalCurrencyId(userVo.getLocalCurrencyId());
        Tuple3<List<MultiColumnLedgerVo>, Subject, List<Subject>> dataTuple = accountBookService.multiColumnLedger(queryVo);
        if (download) {
            try {
                if ("xlsx".equals(type)) {
                    JxlsUtils.exportExcel("template/multiColumnLedger.xlsx", response.getOutputStream(), Dict.create()
                            .set("astName", accountSets.getCompanyName())
                            .set("dateS", queryVo.getStart().format(DateTimeFormatter.ofPattern("yyyy年MM期")))
                            .set("dateE", queryVo.getEnd().format(DateTimeFormatter.ofPattern("yyyy年MM期")))
                            .set("dataList", dataTuple.getT1())
                            .set("subject", dataTuple.getT2())
                            .set("subjectList", dataTuple.getT3()));
                } else if ("pdf".equals(type)) {
                    Rectangle rect = new Rectangle(PageSize.A4.rotate());
                    Document document = new Document(rect);
                    document.addTitle("现金记账凭证");
                    ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
                    PdfWriter writer = PdfWriter.getInstance(document, byteOutputStream);
                    writer.setPdfVersion(PdfWriter.PDF_VERSION_1_7);
                    document.open();
                    response.setContentType("application/pdf");
                    document.add(PdfFontUtils.getFont(1, "账户资金报表", 1));
                    PdfPTable table = new PdfPTable(new float[]{1, 1, 1, 1});
                    table.setWidthPercentage(100);
                    PdfPCellBuilder.builder("编制单位：" + accountSets.getCompanyName())
                            .noBorder().addCell(table);
                    PdfPCellBuilder.builder("科目：" + dataTuple.getT2().getCode() + " " + dataTuple.getT2().getName())
                            .noBorder().addCell(table);
                    PdfPCellBuilder.builder(queryVo.getStart().format(DateTimeFormatter.ofPattern("yyyy年MM期")) + "至" + queryVo.getEnd().format(DateTimeFormatter.ofPattern("yyyy年MM期")))
                            .noBorder().addCell(table);
                    PdfPCellBuilder.builder("单位：元")
                            .horizontalRightAlignment().noBorder().addCell(table);
                    document.add(table);
                    float[] dataCols = new float[7 + dataTuple.getT3().size()];
                    for (int i = 0; i < 7 + dataTuple.getT3().size(); i++) {
                        dataCols[i] = 1;
                    }

                    PdfPTable dataTable = new PdfPTable(dataCols);
                    dataTable.setWidthPercentage(100);
                    String[] columnTitles = {"日期", "凭证字号", "摘要", "借方", "贷方", "方向", "余额"};
                    for (String columnTitle : columnTitles) {
                        PdfPCellBuilder.builder(columnTitle)
                                .rowspan(2)
                                .horizontalCenterAlignment().addCell(dataTable);
                    }

                    PdfPCellBuilder.builder(dataTuple.getT2().getBalanceDirection() + "方")
                            .horizontalCenterAlignment()
                            .colspan(dataTuple.getT3().size())
                            .addCell(dataTable);

                    for (Subject subject : dataTuple.getT3()) {
                        PdfPCellBuilder.builder(subject.getName())
                                .horizontalCenterAlignment()
                                .addCell(dataTable);
                    }

                    for (MultiColumnLedgerVo vo : dataTuple.getT1()) {
                        PdfPCellBuilder.builder(vo.getVoucherDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                                .addCell(dataTable);
                        PdfPCellBuilder.builder(Optional.ofNullable(vo.getWord()).orElse(" "))
                                .horizontalCenterAlignment().addCell(dataTable);
                        PdfPCellBuilder.builder(vo.getSummary()).addCell(dataTable);
                        PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(vo.getDebitAmount().doubleValue()))
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(vo.getCreditAmount().doubleValue()))
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfPCellBuilder.builder(vo.getBalanceDirection()).horizontalCenterAlignment().addCell(dataTable);
                        PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(vo.getBalance().doubleValue()))
                                .horizontalRightAlignment().addCell(dataTable);
                        for (Subject subject : dataTuple.getT3()) {
                            BigDecimal bigDecimal = vo.getSubjectAmount().get(subject.getCode());
                            PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(Optional.ofNullable(bigDecimal).orElse(BigDecimal.ZERO).doubleValue()))
                                    .horizontalRightAlignment()
                                    .addCell(dataTable);
                        }
                    }

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
                log.error("多栏账导出异常", e);
                return JsonResult.failure(e.getMessage());
            }
            return null;
        }
        return JsonResult.successful(dataTuple);
    }

    /**
     * 账龄分析
     *
     * @return
     */
    @PostMapping("ageAnalysis")
    @SaCheckPermission("accountbook-journal-canview")
    public Object ageAnalysis(@RequestBody AgeAnalysisQueryVo queryVo,
                              @RequestParam(defaultValue = "false") Boolean download,
                              @RequestParam(defaultValue = "xlsx") String type,
                              @SessionUser UserVo userVo,
                              @SessionAccountSet AccountSets accountSets,
                              HttpServletResponse response) throws InterruptedException {
        queryVo.setAccountSetsId(accountSets.getId());
        queryVo.setLocalCurrencyId(userVo.getLocalCurrencyId());
        List<AgeAnalysisVo> ageAnalysisVos = accountBookService.ageAnalysis(queryVo);

        if (download) {
            Subject subject = subjectService.getById(new PrimaryKey(queryVo.getSubjectId(), accountSets.getId()));
            try {
                OutputStream outputStream = new ByteArrayOutputStream();
                if ("xlsx".equals(type)) {
                    JxlsUtils.exportExcel("template/ageAnalysis.xlsx", outputStream, Dict.create()
                            .set("astName", accountSets.getCompanyName())
                            .set("dateS", queryVo.getStart().format(DateTimeFormatter.ofPattern("yyyy年MM期")))
                            .set("dateE", queryVo.getEnd().format(DateTimeFormatter.ofPattern("yyyy年MM期")))
                            .set("dataList", ageAnalysisVos)
                            .set("daysSize", queryVo.getDays().size() * 2)
                            .set("days", queryVo.getDayHeader())
                            .set("subject", subject));
                } else if ("pdf".equals(type)) {
                    Rectangle rect = new Rectangle(PageSize.A4.rotate());
                    Document document = new Document(rect);
                    document.addTitle("账龄分析");
                    ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
                    PdfWriter writer = PdfWriter.getInstance(document, byteOutputStream);
                    writer.setPdfVersion(PdfWriter.PDF_VERSION_1_7);
                    document.open();
                    response.setContentType("application/pdf");
                    document.add(PdfFontUtils.getFont(1, "账龄分析表", 1));
                    PdfPTable table = new PdfPTable(new float[]{1, 1, 1});
                    table.setWidthPercentage(100);
                    PdfPCellBuilder.builder("编制单位：" + accountSets.getCompanyName())
                            .noBorder().addCell(table);
                    PdfPCellBuilder.builder(queryVo.getStart().format(DateTimeFormatter.ofPattern("yyyy年MM期")) + "至" + queryVo.getEnd().format(DateTimeFormatter.ofPattern("yyyy年MM期")))
                            .horizontalCenterAlignment()
                            .noBorder().addCell(table);
                    PdfPCellBuilder.builder("单位：元")
                            .horizontalRightAlignment().noBorder().addCell(table);
                    document.add(table);
                    float[] dataCols = new float[9 + queryVo.getDays().size() * 2];
                    Arrays.fill(dataCols, 1);

                    PdfPTable dataTable = new PdfPTable(dataCols);
                    dataTable.setWidthPercentage(100);
                    String[] columnTitles = {"编码", "名称", "期初余额", "借方", "贷方"};
                    for (String columnTitle : columnTitles) {
                        PdfPCellBuilder.builder(columnTitle)
                                .rowspan(2)
                                .horizontalCenterAlignment().addCell(dataTable);
                    }

                    PdfPCellBuilder.builder("期末余额")
                            .colspan(2)
                            .horizontalCenterAlignment().addCell(dataTable);

                    for (Dict dict : queryVo.getDayHeader()) {
                        PdfPCellBuilder.builder(dict.getStr("title"))
                                .colspan(2)
                                .horizontalCenterAlignment().addCell(dataTable);
                    }

                    PdfPCellBuilder.builder("金额")
                            .horizontalCenterAlignment().addCell(dataTable);
                    PdfPCellBuilder.builder("比例")
                            .horizontalCenterAlignment().addCell(dataTable);

                    for (Dict dict : queryVo.getDayHeader()) {
                        PdfPCellBuilder.builder("金额")
                                .horizontalCenterAlignment().addCell(dataTable);
                        PdfPCellBuilder.builder("比例")
                                .horizontalCenterAlignment().addCell(dataTable);

                    }

                    for (AgeAnalysisVo vo : ageAnalysisVos) {
                        PdfPCellBuilder.builder(vo.getCode()).addCell(dataTable);
                        PdfPCellBuilder.builder(vo.getName()).addCell(dataTable);
                        PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(vo.getInitial().doubleValue()))
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(vo.getDebit().doubleValue()))
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(vo.getCredit().doubleValue()))
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(vo.getTotal().doubleValue()))
                                .horizontalRightAlignment().addCell(dataTable);
                        PdfPCellBuilder.builder(NumberUtil.roundStr(vo.getTotalPercent().doubleValue(), 2) + "%")
                                .horizontalRightAlignment().addCell(dataTable);
                        for (Dict dict : queryVo.getDayHeader()) {
                            AgeAnalysisVo.AgeDetail detail = vo.getAges().get(dict.getInt("day"));
                            if (detail == null) {
                                PdfPCellBuilder.builder("")
                                        .horizontalRightAlignment().addCell(dataTable);
                                PdfPCellBuilder.builder("")
                                        .horizontalRightAlignment().addCell(dataTable);
                            } else {
                                PdfPCellBuilder.builder(NumberUtil.decimalFormatMoney(detail.getAmount().doubleValue()))
                                        .horizontalRightAlignment().addCell(dataTable);
                                PdfPCellBuilder.builder(NumberUtil.roundStr(detail.getPercent(), 2) + "%")
                                        .horizontalRightAlignment().addCell(dataTable);
                            }
                        }
                    }

                    document.add(dataTable);
                    document.close();
                    HttpHeaders httpHeaders = new HttpHeaders();
                    String fileName = "账龄分析.pdf";
                    httpHeaders.setContentType(MediaType.APPLICATION_PDF);
                    httpHeaders.set("X-UA-Compatible", "IE=Edge");
                    httpHeaders.set(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8));
                    return new ResponseEntity<>(byteOutputStream.toByteArray(),
                            httpHeaders,
                            HttpStatus.OK);
                }
            } catch (Exception e) {
                log.error("账龄导出异常", e);
                return JsonResult.failure(e.getMessage());
            }
            return null;
        }
        return JsonResult.successful(ageAnalysisVos);
    }
}

