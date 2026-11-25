package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 税务年报记录表（财务报表年报 + 企业所得税年报）
 *
 * <p>一个账套每个税年一条记录，OC 以单一任务完成
 * 财务报表年报和居民企业所得税年报的申报。</p>
 *
 * <pre>
 * declare_status 说明：
 *   pending  - 待申报（任务已创建，等待下发给 OC）
 *   running  - 申报中（OC 已领取任务，正在操作中）
 *   done     - 申报成功
 *   error    - 申报失败（errorMsg 中有原因）
 *   skipped  - 本期跳过（如本期无需申报）
 * </pre>
 */
@Getter
@Setter
@Entity
@Table(name = "task_tax_annual_item",
        uniqueConstraints = @UniqueConstraint(columnNames = {"account_set_id", "tax_year"}))
@Comment("税务年报记录-财务报表年报+企业所得税年报")
public class TaskTaxAnnualItem implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;

    @Comment("关联 task_period.id")
    @Column(name = "task_period_id", nullable = false)
    private Long taskPeriodId;

    @Comment("关联 account_sets.id")
    @Column(name = "account_set_id", nullable = false)
    private Long accountSetId;

    @Comment("申报所属税年，如 2025")
    @Column(name = "tax_year", length = 4, nullable = false)
    private String taxYear;

    @Comment("关联 oc_task.id（申报任务），用于追溯 OC 实例和原始结果")
    @Column(name = "oc_task_id")
    private Long ocTaskId;

    @Comment("年报类型：all（财务报表+企业所得税合并下发，当前默认） / financial_statement / enterprise_income_tax")
    @Column(name = "report_type", length = 50, nullable = false)
    @ColumnDefault("'all'")
    private String reportType = "all";

    @Comment("申报状态：pending / running / done / error / skipped")
    @Column(name = "declare_status", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String declareStatus = "pending";

    @Comment("申报截图URL列表（JSON数组，如 [\"url1\",\"url2\"]）")
    @Column(name = "screenshot_urls", columnDefinition = "TEXT")
    private String screenshotUrls;

    @Comment("申报结果（JSON），由 OC 回调时写入，原始保留")
    @Column(name = "declare_result", columnDefinition = "TEXT")
    private String declareResult;

    @Comment("OC 回调 result.tax_items JSON（企业所得税应纳税额等，格式：[{\"税种名称\":\"企业所得税\",\"应纳税额\":\"12345.00\",\"滞纳金\":\"0.00\"}]）")
    @Column(name = "tax_items", columnDefinition = "TEXT")
    private String taxItems;

    @Comment("错误信息（error 时填入）")
    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Comment("执行时间")
    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Comment("创建时间")
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Comment("最后更新时间")
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
