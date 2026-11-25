package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * FN-FLOW-BATCH-STATE：桌面批量批次（一次"勾选 N 户批量做账/报税"= 一个 batch）。
 * <p>物理表名 {@code fxy_financial_flow_batch}。三态明细在 {@link FlowBatchItem}。
 */
@Getter
@Setter
@Entity
@Table(name = "flow_batch", indexes = @Index(name = "ix_batch_period", columnList = "period"))
@Comment("桌面批量批次")
public class FlowBatch implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Comment("账期 YYYY-MM")
    @Column(name = "period", length = 7, nullable = false)
    private String period;

    @Comment("发起人标识")
    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Comment("批次户数")
    @Column(name = "total", nullable = false)
    private Integer total = 0;

    @Comment("来源：desktop / server-autopilot")
    @Column(name = "source", length = 24)
    private String source;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
