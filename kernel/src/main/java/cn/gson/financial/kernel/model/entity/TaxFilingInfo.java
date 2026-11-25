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
 * 报税信息表 - 每个账套唯一一条记录，存放账套维度的报税设置与备注。
 *
 * <p>字段来源：原 {@code account_sets} 上的两个办理开关 +
 * 原 {@code tax_filing_status} 上的办税+旧账、性质、备注字段；
 * 由于这些字段在业务上不随账期变化，统一收敛到本表。</p>
 */
@Getter
@Setter
@Entity
@DynamicInsert
@DynamicUpdate
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"account_set_id"}))
@Comment("报税信息")
public class TaxFilingInfo implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键，自增")
    private Long id;

    @Comment("账套ID，关联 account_sets.id；账套唯一")
    @Column(name = "account_set_id", nullable = false)
    private Long accountSetId;

    @Comment("是否办理工资薪金")
    @Column(name = "salary_wages_enabled", nullable = false)
    @ColumnDefault("b'0'")
    private Boolean salaryWagesEnabled;

    @Comment("是否办理经营所得")
    @Column(name = "business_income_enabled", nullable = false)
    @ColumnDefault("b'0'")
    private Boolean businessIncomeEnabled;

    @Comment("办税+旧账（合并描述）")
    @Column(name = "tax_handling_old_account", length = 255)
    private String taxHandlingOldAccount;

    @Comment("性质")
    @Column(name = "nature", length = 255)
    private String nature;

    @Comment("备注")
    @Column(length = 255)
    private String remark;

    @Comment("备注1")
    @Column(name = "remark1", length = 255)
    private String remark1;

    @Comment("备注2")
    @Column(name = "remark2", length = 255)
    private String remark2;

    @Comment("备注3")
    @Column(name = "remark3", length = 255)
    private String remark3;

    @Comment("备注4")
    @Column(name = "remark4", length = 255)
    private String remark4;

    @Comment("记录创建时间")
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Comment("最后更新时间")
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
