package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * FN-NEW-01 贷款申请（小程序改版 mock）
 * <p>
 * 状态机：
 *   pending_evaluation → evaluated → submitted → under_review → approved / rejected
 * <p>
 * 物理表名 fxy_financial_loan_application（PhysicalNamingStrategy 自动加前缀）。
 */
@Getter
@Setter
@Entity
@Table(name = "loan_application",
        indexes = {
                @Index(name = "idx_customer", columnList = "customer_id"),
                @Index(name = "idx_status", columnList = "status"),
                @Index(name = "idx_account_sets_status", columnList = "account_sets_id, status")
        })
@Comment("贷款申请（FN-NEW-01 mock）")
public class LoanApplication implements Serializable {

    public static final String STATUS_PENDING_EVALUATION = "pending_evaluation";
    public static final String STATUS_EVALUATED          = "evaluated";
    public static final String STATUS_SUBMITTED          = "submitted";
    public static final String STATUS_UNDER_REVIEW       = "under_review";
    public static final String STATUS_APPROVED           = "approved";
    public static final String STATUS_REJECTED           = "rejected";

    public static final String TYPE_CREDIT   = "credit";
    public static final String TYPE_MORTGAGE = "mortgage";
    public static final String TYPE_DISCOUNT = "discount";

    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键（雪花 ID）")
    private Long id;

    @Comment("账套 ID")
    @Column(name = "account_sets_id", nullable = false)
    private Long accountSetsId;

    @Comment("客户 ID")
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Comment("贷款类型 credit/mortgage/discount")
    @Column(name = "loan_type", length = 32, nullable = false)
    private String loanType;

    @Comment("申请金额")
    @Column(name = "apply_amount", precision = 15, scale = 2)
    private BigDecimal applyAmount;

    @Comment("评估金额")
    @Column(name = "evaluated_amount", precision = 15, scale = 2)
    private BigDecimal evaluatedAmount;

    @Comment("利率（%）")
    @Column(name = "interest_rate", precision = 5, scale = 2)
    private BigDecimal interestRate;

    @Comment("期限（月）")
    @Column(name = "term_months")
    private Integer termMonths;

    @Comment("贷款用途")
    @Column(name = "purpose", columnDefinition = "TEXT")
    private String purpose;

    @Comment("状态机")
    @Column(name = "status", length = 32, nullable = false)
    @ColumnDefault("'pending_evaluation'")
    private String status = STATUS_PENDING_EVALUATION;

    @Lob
    @Comment("申请材料 JSON")
    @Column(name = "materials_json", columnDefinition = "LONGTEXT")
    private String materialsJson;

    @Comment("拒绝原因")
    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Comment("合作银行")
    @Column(name = "bank_partner", length = 64)
    private String bankPartner;

    @Comment("合同号")
    @Column(name = "contract_no", length = 64)
    private String contractNo;

    @Comment("创建时间")
    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @Comment("更新时间")
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
