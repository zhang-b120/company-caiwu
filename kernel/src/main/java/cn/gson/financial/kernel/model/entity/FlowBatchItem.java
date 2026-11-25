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
 * FN-FLOW-BATCH-STATE：批次内单户三态（queued/running/done/failed/manual）+ 当前步骤。
 * <p>物理表名 {@code fxy_financial_flow_batch_item}。幂等键 {@code (batch_id, account_set_id, period)}。
 */
@Getter
@Setter
@Entity
@Table(name = "flow_batch_item",
        uniqueConstraints = @UniqueConstraint(name = "uk_batch_acc_period",
                columnNames = {"batch_id", "account_set_id", "period"}),
        indexes = @Index(name = "ix_item_batch", columnList = "batch_id"))
@Comment("批次内单户三态")
public class FlowBatchItem implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "account_set_id", nullable = false)
    private Long accountSetId;

    @Column(name = "period", length = 7, nullable = false)
    private String period;

    @Comment("queued/running/done/failed/manual")
    @Column(name = "status", length = 20, nullable = false)
    @ColumnDefault("'queued'")
    private String status = "queued";

    @Comment("当前所处步骤")
    @Column(name = "current_stage", length = 20)
    private String currentStage;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
