package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 期初导入结果明细表（步骤2详情）
 *
 * <p>与 task_period 1:1，由人工上传或 OC 推送期初数据后写入。
 * 记录导入来源、科目数、余额条数、成功/失败及错误原因。</p>
 *
 * <pre>
 * source 说明：
 *   manual - 人工上传 Excel 文件
 *   oc     - OC 自动推送
 * </pre>
 */
@Getter
@Setter
@Entity
@Table(name = "task_init_result")
@Comment("期初导入结果-步骤2详情")
public class TaskInitResult implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;

    @Comment("关联 task_period.id，唯一")
    @Column(name = "task_period_id", nullable = false, unique = true)
    private Long taskPeriodId;

    @Comment("关联 oc_task.id（OC推送时填入，人工上传为null）")
    @Column(name = "oc_task_id")
    private Long ocTaskId;

    @Comment("文件来源：manual（人工上传）/ oc（OC推送）")
    @Column(name = "source", length = 20)
    private String source;

    @Comment("导入科目数")
    @Column(name = "subject_count")
    private Integer subjectCount;

    @Comment("导入余额条数")
    @Column(name = "balance_count")
    private Integer balanceCount;

    @Comment("是否导入成功")
    @Column(name = "success")
    private Boolean success;

    @Comment("错误原因详情（失败时填入）")
    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Comment("执行时间")
    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Comment("最后更新时间")
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
