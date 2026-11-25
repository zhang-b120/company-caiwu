package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * FN-FLOW-CLAIM：跨进程执行锁 / 登录锁。一把锁 = (account_set_id, period, stage)。
 * <p>物理表名 {@code fxy_financial_flow_claim}。UNIQUE(account_set_id, period, stage) 保证同一把锁只一行；
 * 抢锁串行化靠 {@code flow_period} 行级写锁（{@link FlowPeriod}）。{@code expires_at} 为 TTL 兜底（持有者崩溃后过期可被接管）。
 * <p>stage 既可为执行步骤（如 declare_vat / full-flow），也可承载办税员登录指纹（如 {@code login:138xxxx}）。
 */
@Getter
@Setter
@Entity
@Table(name = "flow_claim",
        uniqueConstraints = @UniqueConstraint(name = "uk_claim", columnNames = {"account_set_id", "period", "stage"}))
@Comment("跨进程执行锁/登录锁")
public class FlowClaim implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_set_id", nullable = false)
    private Long accountSetId;

    @Column(name = "period", length = 7, nullable = false)
    private String period;

    @Comment("锁维度：执行步骤 或 办税员登录指纹")
    @Column(name = "stage", length = 64, nullable = false)
    private String stage;

    @Comment("占用者标识（进程/桌面会话/操作员）")
    @Column(name = "owner", length = 100)
    private String owner;

    @Comment("抢到锁时间")
    @Column(name = "claimed_at", nullable = false)
    private LocalDateTime claimedAt;

    @Comment("过期时间（claimed_at + TTL），到点后可被接管，防死锁")
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
