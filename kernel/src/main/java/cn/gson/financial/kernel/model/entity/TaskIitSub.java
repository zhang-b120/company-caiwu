package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 个税申报子任务表（步骤6详情）
 *
 * <p>每个账期最多 2 条：comprehensive（综合所得）/ business（经营所得）。
 * task_period.step_tax_iit 的值由本表 2 条状态中最差值聚合。</p>
 *
 * <pre>
 * sub_type 说明：
 *   comprehensive → 综合所得申报-A表（工资薪金、劳务报酬等）
 *   business_b    → 经营所得申报-B表（个人独资企业、合伙企业查账征收）
 *   business_c    → 经营所得申报-C表（合伙企业合伙人分配所得）
 * </pre>
 *
 * <p>对应 OC 任务类型（oc_task.task_type）：
 *   declare_iit_comprehensive / declare_iit_business_b / declare_iit_business_c</p>
 */
@Getter
@Setter
@Entity
@Table(name = "task_iit_sub",
        uniqueConstraints = @UniqueConstraint(columnNames = {"task_period_id", "sub_type"}))
@Comment("个税申报子任务-步骤6详情")
public class TaskIitSub implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;

    @Comment("关联 task_period.id")
    @Column(name = "task_period_id", nullable = false)
    private Long taskPeriodId;

    @Comment("子任务类型：comprehensive（综合所得A表）/ business_b（经营所得B表）/ business_c（经营所得C表）")
    @Column(name = "sub_type", length = 30, nullable = false)
    private String subType;

    @Comment("执行此申报的 oc_task.id，用于追溯 OC 实例和原始结果")
    @Column(name = "oc_task_id")
    private Long ocTaskId;

    @Comment("申报状态：pending / running / done / failed / skipped / na（不适用，如本账套无此税种）")
    @Column(length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String status = "pending";

    @Comment("申报结果（JSON），OC 回调写入")
    @Column(name = "declare_result", columnDefinition = "TEXT")
    private String declareResult;

    @Comment("申报截图URL")
    @Column(name = "screenshot_url", length = 512)
    private String screenshotUrl;

    @Comment("错误信息")
    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Comment("执行时间")
    @Column(name = "executed_at")
    private LocalDateTime executedAt;
}
