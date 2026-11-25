package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 做账运行记录（顶层）。FN-NEW-BOOKKEEPING-RUN-RECORD-PERSISTENCE。
 * <p>每次 xiaowei 做账 / 手动 / 定时触发一次写一行，记录本次做账的整体状态、概览和初始化结果。
 * 与 {@link VoucherReasoningRecord} 通过 {@code runId} 关联（VARCHAR 字符串，由 xiaowei 生成）。
 */
@Getter
@Setter
@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uk_run_id", columnNames = {"runId"})
})
@DynamicInsert
@DynamicUpdate
@Comment("做账运行记录")
public class BookkeepingRunRecord extends AbsEntity {

    @Comment("账期 YYYY-MM")
    @Column(nullable = false, length = 10)
    private String period;

    @Comment("xiaowei 端 job_id（全局唯一）")
    @Column(nullable = false, length = 64)
    private String runId;

    @Comment("做账触发时间")
    @Column(nullable = false)
    private LocalDateTime runAt;

    @Comment("触发来源：xiaowei / manual / scheduled")
    @Column(length = 64)
    private String triggeredBy;

    @Comment("状态：success / partial_failed / failed")
    @Column(nullable = false, length = 32)
    private String status;

    @Comment("中文概览")
    @Column(columnDefinition = "TEXT")
    private String summaryCn;

    @Comment("本次生成凭证总数")
    @Column(nullable = false)
    @ColumnDefault("0")
    private Integer totalVouchers;

    @Comment("本次凭证总金额")
    @Column(precision = 20, scale = 2)
    private BigDecimal totalAmount;

    /** JSON 数组透传：xiaowei 端定义结构，FN 不解析 */
    @Comment("失败步骤")
    @Column(columnDefinition = "json")
    private String failedSteps;

    /** JSON 对象透传：首次做账时的初始化结果（xiaowei init_summary） */
    @Comment("初始化结果")
    @Column(columnDefinition = "json")
    private String configInitSummary;

    @Comment("记录创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;
}
