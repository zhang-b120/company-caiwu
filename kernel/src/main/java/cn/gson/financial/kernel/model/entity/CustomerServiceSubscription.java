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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * FN-NEW-03 客户服务订阅（小程序首页 6 宫格据此显示"已开通/未开通"badge）。
 * <p>
 * 物理表名 fxy_financial_customer_service_subscription（PhysicalNamingStrategy 自动加前缀）。
 * 唯一约束 (customer_id, service_type) — 同客户同类型只一条。
 */
@Getter
@Setter
@Entity
@Table(name = "customer_service_subscription",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_customer_service",
                        columnNames = {"customer_id", "service_type"})
        },
        indexes = {
                @Index(name = "idx_status", columnList = "status")
        })
@Comment("客户服务订阅（FN-NEW-03 mock）")
public class CustomerServiceSubscription implements Serializable {

    public static final String STATUS_ACTIVE  = "active";
    public static final String STATUS_EXPIRED = "expired";
    public static final String STATUS_PENDING = "pending";

    public static final String TYPE_BOOKKEEPING = "bookkeeping";
    public static final String TYPE_GOV         = "gov";
    public static final String TYPE_INVOICE     = "invoice";
    public static final String TYPE_LOAN        = "loan";
    public static final String TYPE_TAX_CHECK   = "tax_check";
    public static final String TYPE_REPORT      = "report";

    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键（雪花 ID）")
    private Long id;

    @Comment("客户 ID")
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Comment("账套 ID")
    @Column(name = "account_sets_id", nullable = false)
    private Long accountSetsId;

    @Comment("服务类型")
    @Column(name = "service_type", length = 32, nullable = false)
    private String serviceType;

    @Comment("状态 active/expired/pending")
    @Column(name = "status", length = 16, nullable = false)
    @ColumnDefault("'pending'")
    private String status = STATUS_PENDING;

    @Comment("生效日期")
    @Column(name = "start_date")
    private LocalDate startDate;

    @Comment("到期日期")
    @Column(name = "end_date")
    private LocalDate endDate;

    @Comment("套餐名")
    @Column(name = "plan_name", length = 64)
    private String planName;

    @Comment("创建时间")
    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @Comment("更新时间")
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
