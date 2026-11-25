package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 报税情况表 - 每个账套每个报税账期一条记录，记录该账期的报税金额与文字说明。
 *
 * <p>账套级配置（办理开关、办税+旧账、性质、备注）已迁出至
 * {@link TaxFilingInfo}（按账套唯一）；本表仅保留按账期变化的字段。</p>
 */
@Getter
@Setter
@Entity
@DynamicInsert
@DynamicUpdate
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"account_set_id", "tax_period"}))
@Comment("报税情况")
public class TaxFilingStatus implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键，自增")
    private Long id;

    @Comment("账套ID，关联 account_sets.id")
    @Column(name = "account_set_id", nullable = false)
    private Long accountSetId;

    @Comment("报税账期，格式 YYYY-MM，如 2026-05")
    @Column(name = "tax_period", length = 7, nullable = false)
    private String taxPeriod;

    @Comment("工资薪金（文字说明）")
    @Column(name = "salary_wages", length = 255)
    private String salaryWages;

    @Comment("工资薪金税款（自由文本）")
    @Column(name = "salary_wages_tax", length = 255)
    private String salaryWagesTax;

    @Comment("经营所得（文字说明）")
    @Column(name = "business_income", length = 255)
    private String businessIncome;

    @Comment("经营所得税款（自由文本）")
    @Column(name = "business_income_tax", length = 255)
    private String businessIncomeTax;

    @Comment("国税（文字说明）")
    @Column(name = "state_tax", length = 255)
    private String stateTax;

    @Comment("国税税款（自由文本）")
    @Column(name = "state_tax_amount", length = 255)
    private String stateTaxAmount;

    @Comment("是否已把个税截图发给客户")
    @ColumnDefault("0")
    @Column(name = "iit_screenshot_sent", nullable = false)
    private Boolean iitScreenshotSent = false;

    @Comment("标记已发送时间")
    @Column(name = "iit_screenshot_sent_at")
    private LocalDateTime iitScreenshotSentAt;

    @Comment("操作人 user id")
    @Column(name = "iit_screenshot_sent_by")
    private Long iitScreenshotSentBy;

    @Comment("记录创建时间")
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Comment("最后更新时间")
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
