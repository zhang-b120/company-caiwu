package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * FN-NEW-02 税务检查报告 entity。
 * <p>
 * 风险等级：green（无风险）/ yellow（中度）/ red（高风险）。
 * dimensions_json / findings_json / recommendations_json 由 service 序列化写入。
 */
@Getter
@Setter
@Entity
@Table(name = "tax_check_report",
        indexes = {
                @Index(name = "idx_customer", columnList = "customer_id"),
                @Index(name = "idx_account_sets_period", columnList = "account_sets_id, period_start")
        })
@Comment("税务检查报告（FN-NEW-02 mock）")
public class TaxCheckReport implements Serializable {

    public static final String RISK_GREEN  = "green";
    public static final String RISK_YELLOW = "yellow";
    public static final String RISK_RED    = "red";

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

    @Comment("检查期起")
    @Column(name = "period_start")
    private LocalDate periodStart;

    @Comment("检查期止")
    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Comment("总分（0-100）")
    @Column(name = "overall_score")
    private Integer overallScore;

    @Comment("风险等级 green/yellow/red")
    @Column(name = "risk_level", length = 16)
    private String riskLevel;

    @Lob
    @Comment("9 维度评分 JSON")
    @Column(name = "dimensions_json", columnDefinition = "LONGTEXT")
    private String dimensionsJson;

    @Lob
    @Comment("风险点 JSON")
    @Column(name = "findings_json", columnDefinition = "LONGTEXT")
    private String findingsJson;

    @Lob
    @Comment("建议 JSON")
    @Column(name = "recommendations_json", columnDefinition = "LONGTEXT")
    private String recommendationsJson;

    @Comment("PDF URL（导出后回填）")
    @Column(name = "report_pdf_url", length = 512)
    private String reportPdfUrl;

    @Comment("创建时间")
    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;
}
