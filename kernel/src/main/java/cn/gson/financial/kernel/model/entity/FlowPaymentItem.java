package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * flow 缴款明细。FN-FLOW-01。物理表名 {@code fxy_financial_flow_payment_item}。
 * <p>幂等 upsert 键：{@code (flow_period_id, tax_category, tax_name)}。
 */
@Getter
@Setter
@Entity
@Table(name = "flow_payment_item",
        uniqueConstraints = @UniqueConstraint(name = "uk_period_cat_name",
                columnNames = {"flow_period_id", "tax_category", "tax_name"}),
        indexes = @Index(name = "ix_pay_period", columnList = "flow_period_id"))
@Comment("缴款明细")
public class FlowPaymentItem implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flow_period_id", nullable = false)
    private Long flowPeriodId;

    @Column(name = "account_set_id", nullable = false)
    private Long accountSetId;

    @Column(name = "period", length = 7, nullable = false)
    private String period;

    @Comment("与 flow_declare_item.category 对齐")
    @Column(name = "tax_category", length = 24, nullable = false)
    private String taxCategory;

    @Column(name = "tax_name", length = 100, nullable = false)
    private String taxName;

    @Comment("应缴")
    @Column(name = "tax_amount", precision = 14, scale = 2)
    private BigDecimal taxAmount;

    @Comment("已缴")
    @Column(name = "paid_amount", precision = 14, scale = 2)
    private BigDecimal paidAmount;

    @Comment("未缴")
    @Column(name = "unpaid_amount", precision = 14, scale = 2)
    private BigDecimal unpaidAmount;

    @Comment("paid/unpaid/partial/na")
    @Column(name = "pay_status", length = 20)
    private String payStatus;

    @Column(name = "pay_deadline")
    private LocalDate payDeadline;

    @Comment("缴款回执号")
    @Column(name = "receipt_no", length = 64)
    private String receiptNo;

    @Column(name = "screenshot_urls", columnDefinition = "TEXT")
    private String screenshotUrls;

    @Column(name = "last_run_id")
    private Long lastRunId;

    @Column(name = "queried_at")
    private LocalDateTime queriedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
