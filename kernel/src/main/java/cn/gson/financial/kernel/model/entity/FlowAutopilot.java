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
 * FN-AUTOPILOT-SWITCH：无人值守「自动做账+报税」开关 / 白名单。
 * <p>物理表名 {@code fxy_financial_flow_autopilot}。{@code account_set_id = 0} 行为<b>全局总开关</b>，
 * {@code > 0} 为账套级白名单项。账套生效自动 = 全局 ON 且 该账套 ON。
 */
@Getter
@Setter
@Entity
@Table(name = "flow_autopilot",
        uniqueConstraints = @UniqueConstraint(name = "uk_account", columnNames = "account_set_id"))
@Comment("无人值守自动做账+报税 开关/白名单")
public class FlowAutopilot implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Comment("账套ID；0=全局总开关")
    @Column(name = "account_set_id", nullable = false)
    private Long accountSetId;

    @Comment("自动做账+报税 开关")
    @Column(name = "enabled", nullable = false)
    @ColumnDefault("0")
    private Boolean enabled = false;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
