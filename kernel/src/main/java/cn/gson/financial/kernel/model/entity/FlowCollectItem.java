package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * flow 采集明细（发票/银行/工资）。FN-FLOW-01。物理表名 {@code fxy_financial_flow_collect_item}。
 * <p>幂等 upsert 键：{@code (flow_period_id, source)}。
 */
@Getter
@Setter
@Entity
@Table(name = "flow_collect_item",
        uniqueConstraints = @UniqueConstraint(name = "uk_period_source",
                columnNames = {"flow_period_id", "source"}),
        indexes = @Index(name = "ix_col_period", columnList = "flow_period_id"))
@Comment("采集明细（发票/银行/工资）")
public class FlowCollectItem implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flow_period_id", nullable = false)
    private Long flowPeriodId;

    @Column(name = "account_set_id", nullable = false)
    private Long accountSetId;

    @Column(name = "period", length = 7, nullable = false)
    private String period;

    @Comment("invoice_out/invoice_in/bank/salary")
    @Column(name = "source", length = 24, nullable = false)
    private String source;

    @Comment("pending/running/done/failed/skipped/na")
    @Column(name = "status", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String status = "pending";

    @Comment("采到条数")
    @Column(name = "item_count")
    private Integer itemCount;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "screenshot_urls", columnDefinition = "TEXT")
    private String screenshotUrls;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "last_run_id")
    private Long lastRunId;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
