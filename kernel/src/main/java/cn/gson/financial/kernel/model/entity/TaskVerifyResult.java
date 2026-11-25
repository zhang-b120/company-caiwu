package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 核验税务结果表（步骤1详情）
 *
 * <p>与 task_period 1:1，由 OC 完成核验任务后回写。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "task_verify_result")
@Comment("核验税务结果-步骤1详情")
public class TaskVerifyResult implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;

    @Comment("关联 task_period.id，唯一")
    @Column(name = "task_period_id", nullable = false, unique = true)
    private Long taskPeriodId;

    @Comment("执行此核验的 oc_task.id")
    @Column(name = "oc_task_id")
    private Long ocTaskId;

    @Comment("OC执行核验的时间")
    @Column(name = "verify_time")
    private LocalDateTime verifyTime;

    @Comment("核验状态：success / failed")
    @Column(name = "status", length = 20)
    private String status;

    @Comment("失败原因")
    @Column(name = "error_msg", length = 500)
    private String errorMsg;

    @Comment("纳税人资格，来自 verify_items.qualification.qualification_type，如：增值税一般纳税人")
    @Column(name = "qualification_status", length = 50)
    private String qualificationStatus;

    @Comment("纳税人状态：normal正常 / cancelled注销 / abnormal非正常")
    @Column(name = "taxpayer_status", length = 20)
    private String taxpayerStatus;

    @Comment("是否有欠税")
    @Column(name = "has_arrears")
    private Boolean hasArrears;

    @Comment("是否有应申报税种")
    @Column(name = "has_should_declare")
    private Boolean hasShouldDeclare;

    @Comment("是否有未申报税种")
    @Column(name = "has_unreported")
    private Boolean hasUnreported;

    @Comment("核验结果详情（JSON），包含资质/纳税人状态/应申报/未申报/欠税")
    @Column(name = "verify_items", columnDefinition = "TEXT")
    private String verifyItems;

    @Comment("各页面截图URL（JSON对象，key：qualification/taxpayer_status/should_declare_taxes/current_unreported_taxes/tax_arrears）")
    @Column(name = "screenshots", columnDefinition = "TEXT")
    private String screenshots;

    @Comment("原始HTML和文本内容（JSON，仅用于排查）")
    @Column(name = "raw_data", columnDefinition = "TEXT")
    private String rawData;

    @Comment("结果写入时间")
    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Comment("最后更新时间")
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
