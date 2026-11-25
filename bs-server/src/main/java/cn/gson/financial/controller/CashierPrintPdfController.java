package cn.gson.financial.controller;

import cn.gson.financial.common.ConvertMoney;
import cn.gson.financial.common.PdfFontUtils;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.CashierJournalDto;
import cn.gson.financial.kernel.model.dto.CashierTransferDto;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.CashierJournalService;
import cn.gson.financial.kernel.service.CashierTransferService;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Rectangle;
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
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 *
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年11月06日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@Controller
@RequestMapping("/pdf")
@RequiredArgsConstructor
public class CashierPrintPdfController {
    private final CashierJournalService cashierJournalService;

    private final CashierTransferService cashierTransferService;
    private final DateTimeFormatter df2 = DateTimeFormatter.ofPattern("yyyy年MM月dd日");

    private final DecimalFormat decf = new DecimalFormat("#,##0.00");

    @PostMapping("journal")
    public ResponseEntity<byte[]> printJournal(@RequestBody Long[] journal, @SessionUser UserVo userVo, @SessionAccountSet AccountSets accountSets) throws IOException, DocumentException {
        List<CashierJournalDto> journalDtos = cashierJournalService.getByIdList(accountSets.getId(), journal);
        Rectangle rect = new Rectangle(PageSize.A4);
        Document doc = new Document(rect);
        doc.addTitle("现金记账凭证");
        doc.addAuthor(userVo.getAccountSets().getCompanyName());
        doc.addKeywords("现金记账凭证");
        doc.addCreator(userVo.getRealName());
        ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
        PdfWriter writer = PdfWriter.getInstance(doc, byteOutputStream);
        writer.setPdfVersion(PdfWriter.PDF_VERSION_1_7);
        doc.open();
        for (int i = 0; i < journalDtos.size(); i++) {
            CashierJournalDto journalDto = journalDtos.get(i);
            String dir = "收款";
            if (journalDto.getCredit() != null) {
                dir = "付款";
            }
            doc.add(PdfFontUtils.getFont(1, dir + "凭据"));
            float[] widths = {130, 150, 100, 100};
            PdfPTable table = new PdfPTable(widths);
            table.setLockedWidth(true);
            table.setTotalWidth(480);
            PdfFontUtils.addCell(table, "账户：" + journalDto.getAccountCode() + "-" + journalDto.getAccountName(), 1, PdfPCell.NO_BORDER);
            PdfFontUtils.addCell(table, 1, journalDto.getBillDate().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")), 2, PdfPCell.NO_BORDER);
            PdfFontUtils.addCell(table, 2, "NO." + journalDto.getSerialNumber(), 1, PdfPCell.NO_BORDER);

            PdfFontUtils.addCell(table, "摘要");
            PdfFontUtils.addCell(table, journalDto.getDigest(), 3);

            PdfFontUtils.addCell(table, "大写金额");
            PdfFontUtils.addCell(table, ConvertMoney.convert(journalDto.getAmount().doubleValue()));
            PdfFontUtils.addCell(table, "小写金额");
            PdfFontUtils.addCell(table, NumberUtil.decimalFormatMoney(journalDto.getAmount().doubleValue()));

            PdfFontUtils.addCell(table, "往来单位");
            PdfFontUtils.addCell(table, journalDto.getAccountingCategoryDetailsValue());
            PdfFontUtils.addCell(table, "结算方式");
            PdfFontUtils.addCell(table, journalDto.getSettlementTypeTitle());

            PdfFontUtils.addCell(table, "收支类别");
            PdfFontUtils.addCell(table, journalDto.getIoTypeValue());
            PdfFontUtils.addCell(table, "票据号");
            PdfFontUtils.addCell(table, journalDto.getBillNo());

            PdfFontUtils.addCell(table, "项目");
            PdfFontUtils.addCell(table, journalDto.getProjectName());
            PdfFontUtils.addCell(table, "部门");
            PdfFontUtils.addCell(table, journalDto.getDeptName());

            PdfFontUtils.addCell(table, "备注");
            PdfFontUtils.addCell(table, journalDto.getRemark(), 3);
            doc.add(table);

            if (i % 2 == 0) {
                doc.add(PdfFontUtils.getFont(1, "     "));
                doc.add(PdfFontUtils.getFont(1, "—-                                                         —-"));
                doc.add(PdfFontUtils.getFont(1, "     "));
            } else {
                doc.newPage();
            }
        }
        doc.close();
        HttpHeaders httpHeaders = new HttpHeaders();
        String fileName = "现金记账凭证.pdf";
        httpHeaders.setContentType(MediaType.APPLICATION_PDF);
        httpHeaders.set("X-UA-Compatible", "IE=Edge");
        httpHeaders.set(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=" + java.net.URLEncoder.encode(fileName, "UTF-8"));
        return new ResponseEntity<>(byteOutputStream.toByteArray(),
                httpHeaders,
                HttpStatus.OK);
    }

    @PostMapping("journal/list/{accountId:\\d+}")
    public ResponseEntity<byte[]> printJournalList(@PathVariable Long accountId, @RequestBody CashierJournalService.CashierJournalQuery query, @SessionUser UserVo userVo) throws IOException, DocumentException {
        PageResults<CashierJournalDto> journalDtos = cashierJournalService.list(userVo.getAccountSets().getId(), accountId, new Page().setPageSize(100000), query);
        if (journalDtos.getTotal() > 0) {
            Rectangle rect = new Rectangle(PageSize.A4);
            Document doc = new Document(rect);
            doc.addTitle("现金记账凭证");
            doc.addKeywords("现金记账凭证");
            doc.setMargins(0f, 0f, 0f, 0f);
            ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
            PdfWriter writer = PdfWriter.getInstance(doc, byteOutputStream);
            writer.setPdfVersion(PdfWriter.PDF_VERSION_1_7);
            doc.open();
            CashierJournalDto[] list = journalDtos.getResults().toArray(new CashierJournalDto[]{});
            CashierJournalDto dto = list[0];

            doc.add(PdfFontUtils.getFont(1, dto.getAccountName() + " 日记账"));
            float[] widths = {90, 65, 100, 80, 65, 75, 75, 75};
            PdfPTable table = new PdfPTable(widths);
            table.setLockedWidth(true);
            table.setTotalWidth(570);

            PdfFontUtils.addCell(table, "账户：" + dto.getAccountCode() + "-" + dto.getAccountName(), 2, PdfPCell.NO_BORDER);
            PdfFontUtils.addCell(table, 1, query.start.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")) + "至" + query.end.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")), 4, PdfPCell.NO_BORDER);
            PdfFontUtils.addCell(table, 2, "单位：元", 2, PdfPCell.NO_BORDER);

            PdfFontUtils.addCell(table, "单据编号");
            PdfFontUtils.addCell(table, "日期");
            PdfFontUtils.addCell(table, "摘要");
            PdfFontUtils.addCell(table, "收支类别");
            PdfFontUtils.addCell(table, "往来单位");
            PdfFontUtils.addCell(table, 2, "收入(借方)");
            PdfFontUtils.addCell(table, 2, "支出(贷方)");
            PdfFontUtils.addCell(table, 2, "余额");

            double debit = 0d;
            double credit = 0d;
            double total = 0d;
            for (int i = 0; i < list.length; i++) {
                dto = list[i];
                PdfFontUtils.addCell(table, Optional.ofNullable(dto.getSerialNumber()).orElse(""));
                PdfFontUtils.addCell(table, dto.getBillDate() == null || dto.getInit() ? "" : dto.getBillDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
                PdfFontUtils.addCell(table, dto.getDigest());
                PdfFontUtils.addCell(table, dto.getIoTypeValue());
                PdfFontUtils.addCell(table, Optional.ofNullable(dto.getAccountingCategoryDetailsValue()).orElse(""));
                PdfFontUtils.addCell(table, 2, dto.getDebit() != null ? NumberUtil.decimalFormatMoney(dto.getDebit().doubleValue()) : "");
                PdfFontUtils.addCell(table, 2, dto.getCredit() != null ? NumberUtil.decimalFormatMoney(dto.getCredit().doubleValue()) : "");
                PdfFontUtils.addCell(table, 2, dto.getBalance() != null ? NumberUtil.decimalFormatMoney(dto.getBalance().doubleValue()) : "");

                debit += Optional.ofNullable(dto.getDebit()).orElse(BigDecimal.ZERO).doubleValue();
                credit += Optional.ofNullable(dto.getCredit()).orElse(BigDecimal.ZERO).doubleValue();

                if (i == list.length - 1) {
                    total = Optional.ofNullable(dto.getBalance()).orElse(BigDecimal.ZERO).doubleValue();
                }
            }

            PdfFontUtils.addCell(table, "");
            PdfFontUtils.addCell(table, "");
            PdfFontUtils.addCell(table, "合计");
            PdfFontUtils.addCell(table, "");
            PdfFontUtils.addCell(table, "");
            PdfFontUtils.addCell(table, 2, NumberUtil.roundStr(debit, 2));
            PdfFontUtils.addCell(table, 2, NumberUtil.roundStr(credit, 2));
            PdfFontUtils.addCell(table, 2, NumberUtil.roundStr(total, 2));

            PdfFontUtils.addCell(table, "编制单位：" + userVo.getAccountSets().getCompanyName(), 4, PdfPCell.NO_BORDER);
            PdfFontUtils.addCell(table, 2, "打印日期：" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), 4, PdfPCell.NO_BORDER);
            doc.add(table);
            doc.close();
            HttpHeaders httpHeaders = new HttpHeaders();
            String fileName = "现金记账列表.pdf";
            httpHeaders.setContentType(MediaType.APPLICATION_PDF);
            httpHeaders.set("X-UA-Compatible", "IE=Edge");
            httpHeaders.set(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=" + java.net.URLEncoder.encode(fileName, "UTF-8"));
            return new ResponseEntity<>(byteOutputStream.toByteArray(),
                    httpHeaders,
                    HttpStatus.OK);
        }
        return null;
    }

    /**
     * 转账记录
     *
     * @param page
     * @param accountSetsId
     * @param query
     * @return
     * @throws IOException
     * @throws DocumentException
     */
    @GetMapping("transfer/list")
    public ResponseEntity<byte[]> printTransferList(Page page, @SessionAccountSet AccountSets accountSets, CashierTransferService.TransferQuery query, @SessionUser UserVo userVo) throws IOException, DocumentException {
        PageResults<CashierTransferDto> pageResults = cashierTransferService.list(accountSets.getId(), page, query);

        if (pageResults.getTotal() > 0) {
            Rectangle rect = new Rectangle(PageSize.A4);
            Document doc = new Document(rect);
            doc.addTitle("内部转账");
            doc.addAuthor(userVo.getAccountSets().getCompanyName());
            doc.addSubject(userVo.getEmail());
            doc.addKeywords("内部转账");
            doc.addCreator(userVo.getRealName());
            doc.setMargins(0f, 0f, 0f, 0f);
            ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
            PdfWriter writer = PdfWriter.getInstance(doc, byteOutputStream);
            writer.setPdfVersion(PdfWriter.PDF_VERSION_1_7);
            doc.open();
            CashierTransferDto[] list = pageResults.getResults().toArray(new CashierTransferDto[]{});
            CashierTransferDto dto = list[0];

            doc.add(PdfFontUtils.getFont(1, " 内部转账"));
            float[] widths = {70, 100, 100, 100, 80, 90, 80};
            PdfPTable table = new PdfPTable(widths);
            table.setLockedWidth(true);
            table.setTotalWidth(550);
            String date = query.getStart().format(df2) + " 至 " + query.getEnd().format(df2);
            PdfFontUtils.addCell(table, date, 3, PdfPCell.NO_BORDER);
            PdfFontUtils.addCell(table, 2, "单位：元", 4, PdfPCell.NO_BORDER);

            PdfFontUtils.addCell(table, "日期");
            PdfFontUtils.addCell(table, "摘要");
            PdfFontUtils.addCell(table, "转出账户");
            PdfFontUtils.addCell(table, "转入账户");
            PdfFontUtils.addCell(table, 2, "金额");
            PdfFontUtils.addCell(table, 1, "备注");
            PdfFontUtils.addCell(table, 1, "关联凭证");

            for (int i = 0; i < list.length; i++) {
                dto = list[i];
                PdfFontUtils.addCell(table, dto.getTransferDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
                PdfFontUtils.addCell(table, dto.getDigest());
                PdfFontUtils.addCell(table, dto.getOutAccount());
                PdfFontUtils.addCell(table, dto.getInAccount());
                PdfFontUtils.addCell(table, 2, decf.format(dto.getAmount()));
                PdfFontUtils.addCell(table, 1, dto.getRemark());
                PdfFontUtils.addCell(table, 1, StrUtil.emptyToDefault(dto.getVoucherTitle(), ""));
            }

            PdfFontUtils.addCell(table, "编制单位：" + userVo.getAccountSets().getCompanyName(), 3, PdfPCell.NO_BORDER);
            PdfFontUtils.addCell(table, 2, "打印日期：" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), 4, PdfPCell.NO_BORDER);
            doc.add(table);
            doc.close();
            HttpHeaders httpHeaders = new HttpHeaders();
            String fileName = "内部转账" + date + ".pdf";
            httpHeaders.setContentType(MediaType.APPLICATION_PDF);
            httpHeaders.set("X-UA-Compatible", "IE=Edge");
            httpHeaders.set(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=" + java.net.URLEncoder.encode(fileName, "UTF-8"));
            return new ResponseEntity<>(byteOutputStream.toByteArray(),
                    httpHeaders,
                    HttpStatus.OK);
        }
        return null;
    }
}
