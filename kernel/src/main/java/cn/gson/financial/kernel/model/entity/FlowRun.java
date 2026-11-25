package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * flow 执行流水（每跑一次 RPA 一行，取代 oc_task 的追溯作用）。FN-FLOW-01。
 * <p>物理表名 {@code fxy_financial_flow_run}。无 UNIQUE 键（按需多行追溯，靠 ix 索引检索）。
 */
@Getter
@Setter
@Entity
@Table(name = "flow_run",
        indexes = {
                @Index(name = "ix_run_period_stage", columnList = "flow_period_id,stage"),
                @Index(name = "ix_run_ref", columnList = "ref_type,ref_id")
        })
@Comment("执行流水（每跑一次RPA一行）")
public class FlowRun implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flow_period_id", nullable = false)
    private Long flowPeriodId;

    @Column(name = "account_set_id", nullable = false)
    private Long accountSetId;

    @Column(name = "period", length = 7, nullable = false)
    private String period;

    @Comment("login/verify/init/collect/book/carry/predeclare/declare_vat/declare_iit/payment/archive")
    @Column(name = "stage", length = 20, nullable = false)
    private String stage;

    @Comment("declare_item/payment_item/collect_item/null")
    @Column(name = "ref_type", length = 20)
    private String refType;

    @Comment("关联明细行 id")
    @Column(name = "ref_id")
    private Long refId;

    @Comment("第几次（重试）")
    @Column(name = "attempt_no", nullable = false)
    @ColumnDefault("1")
    private Integer attemptNo = 1;

    @Column(name = "dry_run", nullable = false)
    @ColumnDefault("0")
    private Boolean dryRun = false;

    @Comment("running/done/failed/timeout/skipped")
    @Column(name = "status", length = 20, nullable = false)
    @ColumnDefault("'running'")
    private String status = "running";

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Comment("本次结果（verify/init/book 无明细表者存这）")
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "screenshot_urls", columnDefinition = "TEXT")
    private String screenshotUrls;

    @Comment("关键日志/错误摘要")
    @Column(name = "log_excerpt", columnDefinition = "TEXT")
    private String logExcerpt;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
