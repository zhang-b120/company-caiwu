package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 未勾选发票明细表
 * OC 从税务局下载未勾选发票 Excel 后，逐行存入本表，供前端展示。
 * 不含发票明细行，仅存表头信息。
 */
@DynamicInsert
@DynamicUpdate
@Getter
@Setter
@Entity
@Table(name = "task_unchecked_invoice")
public class TaskUncheckedInvoice extends AbsEntity {

    @Comment("关联 fxy_financial_oc_task.id，记录本批数据来源的 OC 任务")
    @Column(name = "oc_task_id")
    private Long ocTaskId;

    @Comment("数电发票号码")
    @Column(name = "net_invoice_number", length = 50)
    private String netInvoiceNumber;

    @Comment("发票代码")
    @Column(name = "invoice_code", length = 20)
    private String invoiceCode;

    @Comment("发票号码")
    @Column(name = "invoice_number", length = 50)
    private String invoiceNumber;

    @Comment("开票日期")
    @Column(name = "trade_date")
    private LocalDate tradeDate;

    @Comment("金额（不含税）")
    @Column(name = "amount", precision = 18, scale = 2)
    private BigDecimal amount;

    @Comment("票面税额")
    @Column(name = "tax_amount", precision = 18, scale = 2)
    private BigDecimal taxAmount;

    @Comment("有效抵扣税额")
    @Column(name = "deductible_tax_amount", precision = 18, scale = 2)
    private BigDecimal deductibleTaxAmount;

    @Comment("购买方识别号")
    @Column(name = "buyer_tax_no", length = 50)
    private String buyerTaxNo;

    @Comment("销售方名称")
    @Column(name = "seller_name", length = 200)
    private String sellerName;

    @Comment("销售方纳税人识别号")
    @Column(name = "seller_tax_no", length = 50)
    private String sellerTaxNo;

    @Comment("发票来源")
    @Column(name = "invoice_source", length = 50)
    private String invoiceSource;

    @Comment("票种")
    @Column(name = "invoice_type", length = 100)
    private String invoiceType;

    @Comment("发票状态")
    @Column(name = "invoice_state", length = 20)
    private String invoiceState;

    @Comment("红字锁定标志")
    @Column(name = "red_lock_flag", length = 20)
    private String redLockFlag;

    @Comment("转内销证明编号")
    @Column(name = "domestic_sales_cert_no", length = 50)
    private String domesticSalesCertNo;

    @Comment("业务类型")
    @Column(name = "business_type", length = 50)
    private String businessType;

    @Comment("勾选时间")
    @Column(name = "check_time", length = 30)
    private String checkTime;

    @Comment("发票风险等级")
    @Column(name = "risk_level", length = 20)
    private String riskLevel;

    @Comment("风险状态")
    @Column(name = "risk_status", length = 20)
    private String riskStatus;

    @Comment("差额扣除标识")
    @Column(name = "difference_deduction_flag", length = 20)
    private String differenceDeductionFlag;

    @Comment("显示状态：unchecked / checking / checked")
    @Column(name = "show_status", length = 20, nullable = false)
    @ColumnDefault("'unchecked'")
    private String showStatus = "unchecked";
}
