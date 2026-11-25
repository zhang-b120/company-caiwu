package cn.gson.financial.controller;

import cn.gson.financial.common.ConvertMoney;
import cn.gson.financial.common.PdfFontUtils;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.dto.AbDetailDto;
import cn.gson.financial.kernel.model.dto.VoucherDetailsDto;
import cn.gson.financial.kernel.model.dto.VoucherDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.entity.User;
import cn.gson.financial.kernel.model.vo.AccountBookQueryVO;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.AccountBookService;
import cn.gson.financial.kernel.service.SubjectService;
import cn.gson.financial.kernel.service.VoucherService;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.itextpdf.text.*;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年09月12日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@Controller
@RequestMapping("/pdf")
@RequiredArgsConstructor
public class PrintPdfController {

    private final AccountBookService accountBookService;

    private final VoucherService voucherService;

    private final SubjectService subjectService;


    private final DecimalFormat decf = new DecimalFormat("#,##0.00");

    @RequestMapping("voucher")
    @ResponseBody
    public JsonResult print(@RequestBody(required = false) VoucherService.VoucherQuery query, Page page, @RequestParam(value = "id", required = false) List<Long> idList, @SessionUser UserVo userVo, @SessionAccountSet AccountSets accountSets) {
        if (query == null) {
            query = new VoucherService.VoucherQuery();
        }
        query.setAccountSetsId(accountSets.getId());
        if (CollUtil.isNotEmpty(idList)) {
            query.setId(idList);
        }

        return JsonResult.successful(voucherService.list(query, page));
    }

    private void outPage(VoucherDto data, User createMember, AccountSets accountSets, Document doc, int page, BigDecimal debitAmount, BigDecimal creditAmount, AtomicReference<Double> totalItem) throws DocumentException {
        double pages = Math.ceil(data.getDetails().size() / 5d);

        String pageInfo = "";
        if (pages > 1) {
            pageInfo = "(" + page + "/" + (int) pages + ")";
        }

        doc.add(PdfFontUtils.getFont(1, "记账凭证"));
        float[] widths = {130, 150, 100, 100};
        PdfPTable table = new PdfPTable(widths);
        table.setLockedWidth(true);
        table.setTotalWidth(480);

        PdfPTable table3 = new PdfPTable(3);
        table3.setLockedWidth(true);
        table3.setTotalWidth(480);
        int[] width3 = {190, 200, 190};
        table3.setWidths(width3);
        PdfPCell cell31 = new PdfPCell(PdfFontUtils.getFont(6, "单位：" + accountSets.getCompanyName()));
        PdfPCell cell32 = new PdfPCell(PdfFontUtils.getFont(6, "日期：" + data.getVoucherDate().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")), 8));
        String pzh = "凭证号：" + data.getWord() + "-" + data.getCode() + pageInfo;
        if (data.getReceiptNum() != null && data.getReceiptNum() > 0) {
            pzh = "附单据：" + data.getReceiptNum() + "   " + pzh;
        }
        PdfPCell cell33 = new PdfPCell(PdfFontUtils.getFont(6, pzh, 7));

        cell31.setBorder(0);
        cell32.setBorder(0);
        cell33.setBorder(0);
        cell32.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell33.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table3.addCell(cell31);
        table3.addCell(cell32);
        table3.addCell(cell33);
        doc.add(table3);
        //加入空行
        doc.add(PdfFontUtils.getFont(5, ""));

        PdfFontUtils.addCell(table, Element.ALIGN_CENTER, "摘要", 1, PdfPCell.BOX);
        PdfFontUtils.addCell(table, Element.ALIGN_CENTER, "科目", 1, PdfPCell.BOX);
        PdfFontUtils.addCell(table, Element.ALIGN_CENTER, "借方金额", 1, PdfPCell.BOX);
        PdfFontUtils.addCell(table, Element.ALIGN_CENTER, "贷方金额", 1, PdfPCell.BOX);
        int start = (page - 1) * 5;
        int end = start + 5;
        for (int i = start; i < end; i++) {
            VoucherDetailsDto vd = i < data.getDetails().size() ? data.getDetails().get(i) : null;
            PdfFontUtils.addCell(table, vd != null ? vd.getSummary() : " ", 1, PdfPCell.BOX);
            PdfFontUtils.addCell(table, vd != null ? (vd.getSubjectName() + (vd.getAuxiliaryTitle() == null ? "" : vd.getAuxiliaryTitle())) : " ", 1, PdfPCell.BOX);
            PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, vd != null ? (vd.getDebitAmount() != null && !NumberUtil.equals(vd.getDebitAmount(), BigDecimal.ZERO) ? NumberUtil.decimalFormatMoney(vd.getDebitAmount().doubleValue()) : "") : " ", 1, PdfPCell.BOX);
            PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, vd != null ? (vd.getCreditAmount() != null && !NumberUtil.equals(vd.getCreditAmount(), BigDecimal.ZERO) ? NumberUtil.decimalFormatMoney(vd.getCreditAmount().doubleValue()) : "") : " ", 1, PdfPCell.BOX);
        }

        PdfFontUtils.addCell(table, "合计：" + ConvertMoney.convert(debitAmount.doubleValue()), 2, PdfPCell.BOX);
        PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, NumberUtil.decimalFormatMoney(debitAmount.doubleValue()), 1, PdfPCell.BOX);
        PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, NumberUtil.decimalFormatMoney(creditAmount.doubleValue()), 1, PdfPCell.BOX);

        PdfFontUtils.addCell(table, "", 1, PdfPCell.NO_BORDER);
        PdfFontUtils.addCell(table, "", 1, PdfPCell.NO_BORDER);
        PdfFontUtils.addCell(table, "制单人：" + createMember.getRealName(), 1, PdfPCell.NO_BORDER);
        PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, "审核人：" + StrUtil.nullToDefault(data.getAuditMemberName(), "无"), 1, PdfPCell.NO_BORDER);


        doc.add(table);
        if (totalItem.get() % 2 != 0) {
            doc.add(PdfFontUtils.getFont(1, "     "));
            doc.add(PdfFontUtils.getFont(1, "—-                                                         —-"));
            doc.add(PdfFontUtils.getFont(1, "     "));
        } else if (totalItem.get() != 1) {
            doc.newPage();
        }

        totalItem.getAndSet(totalItem.get() + 1);

        if (pages > 1 && pages != page) {
            outPage(data, createMember, accountSets, doc, page + 1, debitAmount, creditAmount, totalItem);
        }
    }


    @GetMapping("voucher/details")
    public ResponseEntity<byte[]> voucherDetails(@SessionAccountSet AccountSets accountSets,
                                                 @RequestParam("code") List<String> codeList,
                                                 AccountBookQueryVO queryVO, @SessionUser UserVo userVo) throws IOException, DocumentException {
        Long accountSetsId = accountSets.getId();
        queryVO.setAccountSetsId(accountSetsId);
        String title = queryVO.getStart().format(DateTimeFormatter.ofPattern("yyyy年MM期"));
        if (!queryVO.getStart().equals(queryVO.getEnd())) {
            title = queryVO.getStart().format(DateTimeFormatter.ofPattern("yyyy年MM期")) + " - " + queryVO.getEnd().format(DateTimeFormatter.ofPattern("yyyy年MM期"));
        }

        Rectangle rect = new Rectangle(queryVO.getShowNumPrice() ? PageSize.A4.rotate() : PageSize.A4);
        Document doc = new Document(rect);
        doc.addTitle(userVo.getAccountSets().getCompanyName() + title + "明细账");
        doc.addAuthor(userVo.getAccountSets().getCompanyName());
        doc.addSubject(userVo.getMobile());
        doc.addKeywords("明细账");
        doc.setMargins(0f, 0f, 0f, 0f);
        ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
        PdfWriter writer = PdfWriter.getInstance(doc, byteOutputStream);
        writer.setPdfVersion(PdfWriter.PDF_VERSION_1_7);
        doc.open();

        Map<String, Subject> subjectMap = subjectService.listByCode(accountSetsId, codeList);
        for (Map.Entry<String, Subject> entry : subjectMap.entrySet()) {
            String subjectCode = entry.getKey();
            Subject subject = entry.getValue();
            try {
                queryVO.setSubjectCode(subjectCode);

                List<AbDetailDto> data = this.accountBookService.details(queryVO, userVo.getAccountSets().getEnableDate());
                if (CollUtil.isNotEmpty(data)) {
                    doc.add(PdfFontUtils.getFont(1, "     "));

                    doc.add(PdfFontUtils.getFont(1, subject.getName() + " " + title + " 明细账"));
                    if (queryVO.getShowNumPrice()) {
                        float[] widths = {70, 60, 150, 120, 70, 70, 70, 70, 70, 70, 40, 70, 70, 70};
                        PdfPTable table = new PdfPTable(widths);
                        table.setLockedWidth(true);
                        table.setTotalWidth(800);
                        PdfFontUtils.addCell(table, "科目：" + subjectCode + " - " + subject.getName(), 3, PdfPCell.NO_BORDER);
                        PdfFontUtils.addCell(table, 2, "单位：元", 11, PdfPCell.NO_BORDER);

                        PdfFontUtils.addCell(table, Element.ALIGN_LEFT, "日期", 1, 2, Rectangle.BOX);
                        PdfFontUtils.addCell(table, Element.ALIGN_CENTER, "凭证字号", 1, 2, Rectangle.BOX);
                        PdfFontUtils.addCell(table, Element.ALIGN_LEFT, "科目", 1, 2, Rectangle.BOX);
                        PdfFontUtils.addCell(table, Element.ALIGN_LEFT, "摘要", 1, 2, Rectangle.BOX);
                        PdfFontUtils.addCell(table, Element.ALIGN_CENTER, "借方", 3, Rectangle.BOX);
                        PdfFontUtils.addCell(table, Element.ALIGN_CENTER, "贷方", 3, Rectangle.BOX);
                        PdfFontUtils.addCell(table, Element.ALIGN_CENTER, "余额", 4, Rectangle.BOX);
                        PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, "数量");
                        PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, "单价");
                        PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, "金额");
                        PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, "数量");
                        PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, "单价");
                        PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, "金额");
                        PdfFontUtils.addCell(table, Element.ALIGN_CENTER, "方向");
                        PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, "数量");
                        PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, "单价");
                        PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, "金额");

                        for (AbDetailDto dto : data) {
                            PdfFontUtils.addCell(table, dto.getVoucherDate() != null ? dto.getVoucherDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) : "");
                            PdfFontUtils.addCell(table, Element.ALIGN_CENTER, dto.getWord() != null ? dto.getWord() + "-" + dto.getCode() : "");
                            PdfFontUtils.addCell(table, dto.getSubjectName() != null ? dto.getSubjectName() : "");
                            PdfFontUtils.addCell(table, Opt.ofNullable(dto.getSummary()).orElse(""));
                            PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, "");
                            PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, "");
                            PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, dto.getDebitAmount() != null ? decf.format(dto.getDebitAmount()) : "");
                            PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, "");
                            PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, "");
                            PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, dto.getCreditAmount() != null ? decf.format(dto.getCreditAmount()) : "");
                            PdfFontUtils.addCell(table, Element.ALIGN_CENTER, StrUtil.emptyToDefault(dto.getBalanceDirection(), ""));
                            PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, "");
                            PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, "");
                            PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, dto.getBalance() != null ? decf.format(dto.getBalance()) : "");
                        }

                        PdfFontUtils.addCell(table, "编制单位：" + userVo.getAccountSets().getCompanyName(), 3, PdfPCell.NO_BORDER);
                        PdfFontUtils.addCell(table, 2, "打印日期：" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), 11, PdfPCell.NO_BORDER);
                        doc.add(table);
                    } else {
                        float[] widths = {70, 60, 150, 120, 70, 70, 40, 70};
                        PdfPTable table = new PdfPTable(widths);
                        table.setLockedWidth(true);
                        table.setTotalWidth(550);
                        PdfFontUtils.addCell(table, "科目：" + subjectCode + " - " + subject.getName(), 3, PdfPCell.NO_BORDER);
                        PdfFontUtils.addCell(table, 2, "单位：元", 5, PdfPCell.NO_BORDER);

                        PdfFontUtils.addCell(table, "日期");
                        PdfFontUtils.addCell(table, Element.ALIGN_CENTER, "凭证字号");
                        PdfFontUtils.addCell(table, "科目");
                        PdfFontUtils.addCell(table, "摘要");
                        PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, "借方");
                        PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, "贷方");
                        PdfFontUtils.addCell(table, Element.ALIGN_CENTER, "方向");
                        PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, "余额");

                        for (AbDetailDto dto : data) {
                            PdfFontUtils.addCell(table, dto.getVoucherDate() != null ? dto.getVoucherDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) : "");
                            PdfFontUtils.addCell(table, Element.ALIGN_CENTER, dto.getWord() != null ? dto.getWord() + "-" + dto.getCode() : "");
                            PdfFontUtils.addCell(table, dto.getSubjectName() != null ? dto.getSubjectName() : "");
                            PdfFontUtils.addCell(table, Opt.ofNullable(dto.getSummary()).orElse(""));
                            PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, dto.getDebitAmount() != null ? decf.format(dto.getDebitAmount()) : "");
                            PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, dto.getCreditAmount() != null ? decf.format(dto.getCreditAmount()) : "");
                            PdfFontUtils.addCell(table, Element.ALIGN_CENTER, StrUtil.emptyToDefault(dto.getBalanceDirection(), ""));
                            PdfFontUtils.addCell(table, Element.ALIGN_RIGHT, dto.getBalance() != null ? decf.format(dto.getBalance()) : "");
                        }

                        PdfFontUtils.addCell(table, "编制单位：" + userVo.getAccountSets().getCompanyName(), 3, PdfPCell.NO_BORDER);
                        PdfFontUtils.addCell(table, 2, "打印日期：" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), 5, PdfPCell.NO_BORDER);
                        doc.add(table);
                    }
                    doc.newPage();
                }
            } catch (Exception e) {
                log.error("数据异常：", e);
            }
        }
        doc.close();
        HttpHeaders httpHeaders = new HttpHeaders();
        String fileName = userVo.getAccountSets().getCompanyName() + title + "明细账.pdf";
        httpHeaders.setContentType(MediaType.APPLICATION_PDF);
        httpHeaders.set("X-UA-Compatible", "IE=Edge");
        httpHeaders.set(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        return new ResponseEntity<>(byteOutputStream.toByteArray(),
                httpHeaders,
                HttpStatus.OK);
    }
}
