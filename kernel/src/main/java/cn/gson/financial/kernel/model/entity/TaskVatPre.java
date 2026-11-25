package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 做账前国税预申报税费明细（vat_declare_pre 回调数据）
 * <p>
 * OC 在做账前执行 vat_declare_pre 任务，获取各税种应缴税费，
 * 回调后写入此表，做账阶段读取生成代缴税费凭证。
 * <p>
 * 字段来源：OC 回调 result.tax_items 数组，每条对应税局"税款征收计划"一行。
 */
@Getter
@Setter
@Entity
@Table(name = "task_vat_pre",
        uniqueConstraints = @UniqueConstraint(columnNames = {"task_period_id", "seq_no"}))
@Comment("做账前国税预申报税费明细")
public class TaskVatPre implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;

    @Comment("关联 task_period.id")
    @Column(name = "task_period_id", nullable = false)
    private Long taskPeriodId;

    @Comment("关联 oc_task.id")
    @Column(name = "oc_task_id")
    private Long ocTaskId;

    @Comment("税局序号（与 task_period_id 联合唯一，用于幂等写入）")
    @Column(name = "seq_no", length = 20, nullable = false)
    private String seqNo;

    @Comment("税（费）种名称（税局原始文本，如 增值税、城市维护建设税）")
    @Column(name = "tax_name", length = 200, nullable = false)
    private String taxName;

    @Comment("税（品）目")
    @Column(name = "tax_item", length = 200)
    private String taxItem;

    @Comment("税款所属期起")
    @Column(name = "tax_period_start")
    private LocalDate taxPeriodStart;

    @Comment("税款所属期止")
    @Column(name = "tax_period_end")
    private LocalDate taxPeriodEnd;

    @Comment("申报日期")
    @Column(name = "declare_date")
    private LocalDate declareDate;

    @Comment("缴款期限")
    @Column(name = "payment_deadline")
    private LocalDate paymentDeadline;

    @Comment("应缴税额（元）")
    @Column(name = "tax_payable", precision = 14, scale = 2)
    private BigDecimal taxPayable;

    @Comment("实缴税额（元）")
    @Column(name = "tax_paid", precision = 14, scale = 2)
    private BigDecimal taxPaid;

    @Comment("滞纳金、利息、违约金、加罚款（元）")
    @Column(name = "surcharge", precision = 14, scale = 2)
    private BigDecimal surcharge;

    @Comment("状态：done / error")
    @Column(name = "status", length = 20)
    @ColumnDefault("'done'")
    private String status = "done";

    @Comment("OC 原始回调结果（JSON）")
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Comment("错误信息")
    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Comment("写入时间")
    @Column(name = "executed_at")
    private LocalDateTime executedAt;
}
