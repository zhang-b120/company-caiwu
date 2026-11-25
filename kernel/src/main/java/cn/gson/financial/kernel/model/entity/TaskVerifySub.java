package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 核验税务子任务表（步骤1详情）
 *
 * <p>每个账期固定 4 条，对应 4 个并行子任务：
 * verify_taxpayer / verify_arrears / verify_undeclared / verify_required_taxes。
 * task_period.step_verify 的值由本表 4 条状态中最差值聚合而来。</p>
 *
 * <pre>
 * sub_type 说明：
 *   verify_taxpayer      → 核验纳税人状态 + 公司性质
 *   verify_arrears       → 查询欠税/待缴情况
 *   verify_undeclared    → 查询当期未申报税种
 *   verify_required_taxes → 获取本账套应申报税种列表
 * </pre>
 */
@Getter
@Setter
@Entity
@Table(name = "task_verify_sub",
        uniqueConstraints = @UniqueConstraint(columnNames = {"task_period_id", "sub_type"}))
@Comment("核验税务子任务-步骤1详情")
public class TaskVerifySub implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键，自增")
    private Long id;

    @Comment("关联 task_period.id")
    @Column(name = "task_period_id", nullable = false)
    private Long taskPeriodId;

    @Comment("子任务类型：verify_taxpayer / verify_arrears / verify_undeclared / verify_required_taxes")
    @Column(name = "sub_type", length = 40, nullable = false)
    private String subType;

    @Comment("执行此子任务的 oc_task.id")
    @Column(name = "oc_task_id")
    private Long ocTaskId;

    @Comment("子任务状态：pending / running / done / error")
    @Column(length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String status = "pending";

    @Comment("结果数据（JSON），各子任务字段不同")
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Comment("OC 截图URL")
    @Column(name = "screenshot_url", columnDefinition = "TEXT")
    private String screenshotUrl;

    @Comment("错误信息")
    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Comment("最后执行时间")
    @Column(name = "executed_at")
    private LocalDateTime executedAt;
}
