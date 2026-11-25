package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.enums.DeclareTaskStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * FN-03 申报任务
 * <p>
 * 给 brower-tax 团队（BT-10/11/12/13）提供"申报任务"统一模型 + 状态机。
 * 物理表名 fxy_financial_declare_task（PhysicalNamingStrategy 自动加前缀）。
 * <p>
 * 状态机由 {@link cn.gson.financial.kernel.service.DeclareTaskService} 在 service 层校验，
 * 任何 setStatus 必须经由 service 的 transitionTo / claim / start / completeWithResult 方法。
 */
@Getter
@Setter
@Entity
@Table(name = "declare_task")
@Comment("申报任务（FN-03）")
public class DeclareTask implements Serializable {

    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键（雪花 ID）")
    private Long id;

    @Comment("账套 ID")
    @Column(name = "account_sets_id", nullable = false)
    private Long accountSetsId;

    @Comment("客户 ID")
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Comment("税种：VAT/PIT/CIT/SURCHARGE/...")
    @Column(name = "tax_type", length = 32, nullable = false)
    private String taxType;

    @Comment("期间-年")
    @Column(name = "period_year", nullable = false)
    private Integer periodYear;

    @Comment("期间-月（季报留空）")
    @Column(name = "period_month")
    private Integer periodMonth;

    @Comment("期间-季（月报留空）")
    @Column(name = "period_quarter")
    private Integer periodQuarter;

    @Comment("省份代码")
    @Column(name = "province", length = 32)
    private String province;

    @Comment("状态机：PENDING/PREPARING/EXECUTING/SUCCESS/FAILED/MANUAL")
    @Column(name = "status", length = 32, nullable = false)
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'PENDING'")
    private DeclareTaskStatus status = DeclareTaskStatus.PENDING;

    @Comment("当前执行者标识（worker name）")
    @Column(name = "executor", length = 64)
    private String executor;

    @Comment("是否 dry_run（不真实提交税局）")
    @Column(name = "dry_run", nullable = false)
    @ColumnDefault("0")
    private Boolean dryRun = Boolean.FALSE;

    @Comment("结果 JSON")
    @Column(name = "result_json", columnDefinition = "LONGTEXT")
    private String resultJson;

    @Comment("错误信息")
    @Column(name = "error_msg", columnDefinition = "LONGTEXT")
    private String errorMsg;

    /**
     * 乐观锁版本字段。
     * <p>
     * 抢占场景（claim）下两个 worker 同时把 PENDING → PREPARING，
     * Hibernate 会基于 version 自动追加 WHERE version=? AND id=?，
     * 后到的会拿到 update 影响行数 0 → 抛 OptimisticLockException → service 转 ALREADY_CLAIMED。
     */
    @Version
    @Comment("乐观锁版本")
    @Column(name = "version", nullable = false)
    @ColumnDefault("0")
    private Long version = 0L;

    @Comment("创建时间")
    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @Comment("更新时间")
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Comment("提交时间（result 接口写入）")
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;
}
