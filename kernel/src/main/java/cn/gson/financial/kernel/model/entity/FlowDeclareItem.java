package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * flow 申报明细（国税 + 个税合一，{@code category} 区分）。FN-FLOW-01。
 * <p>物理表名 {@code fxy_financial_flow_declare_item}。
 * <p>幂等 upsert 键：{@code (flow_period_id, category, tax_name)}。
 */
@Getter
@Setter
@Entity
@Table(name = "flow_declare_item",
        uniqueConstraints = @UniqueConstraint(name = "uk_period_cat_name",
                columnNames = {"flow_period_id", "category", "tax_name"}),
        indexes = @Index(name = "ix_decl_period", columnList = "flow_period_id"))
@Comment("申报明细（国税+个税，category 区分）")
public class FlowDeclareItem implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Comment("关联 flow_period.id")
    @Column(name = "flow_period_id", nullable = false)
    private Long flowPeriodId;

    @Column(name = "account_set_id", nullable = false)
    private Long accountSetId;

    @Column(name = "period", length = 7, nullable = false)
    private String period;

    @Comment("vat/surcharge/cit/stamp/iit_comprehensive/iit_business_b/iit_business_c")
    @Column(name = "category", length = 24, nullable = false)
    private String category;

    @Comment("税局原始税种名")
    @Column(name = "tax_name", length = 200, nullable = false)
    private String taxName;

    @Comment("申报所属期")
    @Column(name = "declare_period", length = 20)
    private String declarePeriod;

    @Column(name = "declare_deadline")
    private LocalDate declareDeadline;

    @Comment("预演=1/真报=0")
    @Column(name = "dry_run", nullable = false)
    @ColumnDefault("0")
    private Boolean dryRun = false;

    @Comment("pending/running/done/failed/skipped/exempt/manual")
    @Column(name = "status", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String status = "pending";

    @Comment("申报税额，负=留抵")
    @Column(name = "declared_amount", precision = 14, scale = 2)
    private BigDecimal declaredAmount;

    @Comment("税局回执号")
    @Column(name = "receipt_no", length = 64)
    private String receiptNo;

    @Comment("申报表/回执原文")
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Comment("JSON数组")
    @Column(name = "screenshot_urls", columnDefinition = "TEXT")
    private String screenshotUrls;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Comment("最近一次 flow_run.id")
    @Column(name = "last_run_id")
    private Long lastRunId;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
