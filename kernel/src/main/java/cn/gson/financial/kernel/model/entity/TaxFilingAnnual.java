package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 报税情况-年报表
 *
 * <p>每个账套每年一条记录，记录该年的企业年报/工商年报与备注。
 * 年份由报税账期的年份自动得到（如 2026-04 → 2026），同年所有月份共享同一条记录。</p>
 */
@Getter
@Setter
@Entity
@DynamicInsert
@DynamicUpdate
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"account_set_id", "tax_year"}))
@Comment("报税情况-年报")
public class TaxFilingAnnual implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键，自增")
    private Long id;

    @Comment("账套ID，关联 account_sets.id")
    @Column(name = "account_set_id", nullable = false)
    private Long accountSetId;

    @Comment("报税年份，如 2026")
    @Column(name = "tax_year", nullable = false)
    private Integer taxYear;

    @Comment("企业年报")
    @Column(name = "enterprise_annual_report", length = 255)
    private String enterpriseAnnualReport;

    @Comment("工商年报")
    @Column(name = "industry_commercial_annual_report", length = 255)
    private String industryCommercialAnnualReport;

    @Comment("年报备注")
    @Column(name = "annual_remark", length = 255)
    private String annualRemark;

    @Comment("记录创建时间")
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Comment("最后更新时间")
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
