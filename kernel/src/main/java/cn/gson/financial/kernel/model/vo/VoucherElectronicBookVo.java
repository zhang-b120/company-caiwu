package cn.gson.financial.kernel.model.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class VoucherElectronicBookVo {

    private Long voucherId;
    private String word;
    private Integer code;
    private LocalDate voucherDate;
    private BigDecimal totalDebit;
    private BigDecimal totalCredit;
    private Integer receiptNum;
    private List<DetailItem> details;
    private List<AttachmentItem> attachments;

    @Data
    public static class DetailItem {
        private Long subjectId;
        private String subjectCode;
        private String subjectName;
        private String summary;
        private BigDecimal debitAmount;
        private BigDecimal creditAmount;
    }

    @Data
    public static class AttachmentItem {
        /** invoice | bank | asset_change | asset_depreciation */
        private String type;
        private Long id;

        // --- 发票字段 ---
        private String invoiceNo;
        /** 往来单位 */
        private String counterparty;
        private LocalDate tradeDate;
        private BigDecimal amount;
        private BigDecimal taxAmount;
        private BigDecimal taxAmountExcluded;
        /** IN=进项  OUT=销项 */
        private String invoiceDirection;
        private String newCategory;
        private List<InvoiceItemDetail> invoiceItems;

        // --- 银行流水字段 ---
        private String transactionType;
        private LocalDateTime bankTradeDate;
        private BigDecimal tradeAmount;
        private BigDecimal balance;
        private String payName;
        private String receiveName;
        private String bankRemarks;

        // --- 固定资产字段 ---
        private String assetName;
        private String assetCode;
        private String changeType;
    }

    @Data
    public static class InvoiceItemDetail {
        private String goodsName;
        private String specification;
        private String unit;
        private Double quantity;
        private String amount;
        private Integer taxRate;
        private BigDecimal tax;
    }
}
