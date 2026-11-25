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
 * 个税申报记录表（步骤6详情）
 *
 * <p>一个账套+期间可有 1~2 条记录（按 iit_type 区分）：
 * 有些公司只报工资薪金，有些只报经营所得，有些两种都报。</p>
 *
 * <pre>
 * iit_type 说明：
 *   salary   - 工资薪金所得（综合所得申报）
 *   business - 经营所得（个体工商户/个人独资等）
 *
 * declare_status 说明：
 *   pending  - 待申报（任务已创建，等待下发给 OC）
 *   running  - 申报中（OC 已领取任务，正在操作中）
 *   done     - 申报成功
 *   error    - 申报失败（errorMsg 中有原因）
 *   skipped  - 本期跳过（如本期无需申报）
 * </pre>
 *
 * <p>task_period.step_tax_iit 由本表所有行的最差状态聚合：
 * error > running > pending > skipped > done。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "task_iit_item",
        uniqueConstraints = @UniqueConstraint(columnNames = {"task_period_id", "iit_type"}))
@Comment("个税申报记录-步骤6详情")
public class TaskIitItem implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;

    @Comment("关联 task_period.id")
    @Column(name = "task_period_id", nullable = false)
    private Long taskPeriodId;

    @Comment("关联 oc_task.id（申报任务），用于追溯 OC 实例和原始结果")
    @Column(name = "oc_task_id")
    private Long ocTaskId;

    @Comment("申报类型：salary（工资薪金所得）/ business（经营所得）")
    @Column(name = "iit_type", length = 20, nullable = false)
    private String iitType;

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
