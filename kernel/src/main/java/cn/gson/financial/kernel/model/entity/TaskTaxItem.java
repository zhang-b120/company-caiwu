package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 国税申报税目表（步骤5详情）
 *
 * <p>国税步骤分两阶段：</p>
 * <ol>
 *   <li>OC 执行 fetch_tax_items → 写入本表（每个税种一行，status=pending）</li>
 *   <li>系统逐个下发 declare_tax_item 任务 → OC 申报后回写每行的 status/result</li>
 * </ol>
 *
 * <p>task_period.step_tax_national 由本表所有行的最差状态聚合。</p>
 * <p>tax_name 使用税局表头原始文本，与 task_period_id 联合唯一，作为幂等 upsert 的 key。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "task_tax_item",
        uniqueConstraints = @UniqueConstraint(columnNames = {"task_period_id", "tax_name"}))
@Comment("国税申报税目-步骤5详情")
public class TaskTaxItem implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;

    @Comment("关联 task_period.id")
    @Column(name = "task_period_id", nullable = false)
    private Long taskPeriodId;

    @Comment("税种名称（税局原始文本，如增值税及附加税费申报；与 task_period_id 联合唯一）")
    @Column(name = "tax_name", length = 200, nullable = false)
    private String taxName;

    @Comment("申报所属期（如 2025-12，可能与账期不同）")
    @Column(name = "declare_period", length = 20)
    private String declarePeriod;

    @Comment("申报截止日期（税局显示，如 2026-01-15）")
    @Column(name = "declare_deadline")
    private java.time.LocalDate declareDeadline;

    @Comment("申报状态：pending（待申报）/ running（申报中）/ done（申报成功）/ failed（申报失败）/ skipped（免申报/跳过）/ exempt（本期无需申报）")
    @Column(name = "declare_status", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String declareStatus = "pending";

    @Comment("申报税额（元，done 后填入；负数表示留抵）")
    @Column(name = "declared_amount", precision = 14, scale = 2)
    private BigDecimal declaredAmount;

    @Comment("申报结果（JSON），由 OC 回调时写入，原始保留。{result_text, form_no, ...}")
    @Column(name = "declare_result", columnDefinition = "TEXT")
    private String declareResult;

    @Comment("申报截图URL列表（JSON数组，如 [\"url1\",\"url2\"]），每个税种可有多张截图")
    @Column(name = "screenshot_urls", columnDefinition = "TEXT")
    private String screenshotUrls;

    @Comment("错误信息（failed 时填入）")
    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Comment("申报完成时间")
    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Comment("执行申报的 oc_task.id（declare_tax_item 任务），用于追溯 OC 实例和原始结果")
    @Column(name = "oc_task_id")
    private Long ocTaskId;

    @Comment("显示排序（fetch_tax_items 返回的顺序）")
    @Column(name = "sort_order")
    @ColumnDefault("0")
    private Integer sortOrder = 0;
}
