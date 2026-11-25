package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 做账子任务表（步骤4详情）
 *
 * <p>每个账期最多 5 条（invoice/bank/salary/asset/carry_forward），由系统内部执行，不经过 OC。
 * task_period.step_bookkeep 的值由本表各条状态中最差值聚合。</p>
 *
 * <pre>
 * sub_type 说明：
 *   invoice → 发票生成凭证（销项/进项发票 → 对应凭证）
 *   bank    → 银行流水生成凭证（银行流水明细 → 收付款凭证）
 *   salary  → 工资生成凭证（工资单 → 计提/发放凭证）
 *   asset   → 固定资产生成凭证（本期折旧 → 折旧凭证）
 * </pre>
 */
@Getter
@Setter
@Entity
@Table(name = "task_bookkeep_sub",
        uniqueConstraints = @UniqueConstraint(columnNames = {"task_period_id", "sub_type"}))
@Comment("做账子任务-步骤4详情")
public class TaskBookkeepSub implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;

    @Comment("关联 task_period.id")
    @Column(name = "task_period_id", nullable = false)
    private Long taskPeriodId;

    @Comment("子任务类型：invoice / bank / salary / asset / carry_forward")
    @Column(name = "sub_type", length = 20, nullable = false)
    private String subType;

    @Comment("状态：pending / running / done / error / skipped（如本期无此类数据则跳过）")
    @Column(length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String status = "pending";

    @Comment("生成凭证数量（done 后填入）")
    @Column(name = "voucher_count")
    private Integer voucherCount;

    @Comment("处理明细数量（处理了多少条发票/流水/工资行/资产）")
    @Column(name = "source_count")
    private Integer sourceCount;

    @Comment("结果描述（如：关键词匹配5张，AI匹配3张，生成凭证8张）")
    @Column(name = "msg", columnDefinition = "TEXT")
    private String msg;

    @Comment("错误信息")
    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Comment("执行时间")
    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Comment("对应 oc_task.id（用于追溯执行记录）")
    @Column(name = "oc_task_id")
    private Long ocTaskId;
}
