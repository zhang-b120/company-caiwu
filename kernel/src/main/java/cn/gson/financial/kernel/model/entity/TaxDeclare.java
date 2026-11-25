package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 应申报税费种信息
 */
@Getter
@Setter
@Entity
@Table(name = "tax_declare")
@Comment("应申报税费种信息")
public class TaxDeclare implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键，自增")
    private Long id;

    @Comment("账套ID，关联 fxy_financial_account_sets.id")
    @Column(name = "account_sets_id", nullable = false)
    private Long accountSetsId;

    @Comment("税（费）种")
    @Column(name = "tax_type", length = 64, nullable = false)
    private String taxType;

    @Comment("税目")
    @Column(name = "tax_item", length = 64, nullable = false)
    private String taxItem;

    @Comment("子目")
    @Column(name = "tax_subitem", length = 64)
    private String taxSubitem;

    @Comment("纳税（费）期限")
    @Column(name = "tax_period", length = 16, nullable = false)
    private String taxPeriod;

    @Comment("申报期限")
    @Column(name = "declare_period", length = 64, nullable = false)
    private String declarePeriod;

    @Comment("认定有效期起")
    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Comment("认定有效期止")
    @Column(name = "valid_to", nullable = false)
    private LocalDate validTo;

    @Comment("主附税标志")
    @Column(name = "main_add_flag", length = 32, nullable = false)
    private String mainAddFlag;

    @Comment("是否实际有该税费认定：1=有，0=无（占位记录）")
    @Column(name = "is_effective", nullable = false)
    private Boolean isEffective = true;

    @Comment("创建时间")
    @CreationTimestamp
    @Column(name = "create_time", updatable = false)
    private LocalDateTime createTime;

    @Comment("更新时间（用户可据此判断是否重新抓取）")
    @UpdateTimestamp
    @Column(name = "update_time")
    private LocalDateTime updateTime;
}
