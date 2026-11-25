package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * FN-LOGIN-LOCK-BLOCKING：办税员登录锁（按 lock_key=办税员登录指纹，跨账套全局互斥）。
 * <p>物理表名 {@code fxy_financial_flow_login_lock}。UNIQUE(lock_key) 保证一把锁一行；
 * {@code expires_at}=claimed_at+TTL 兜底防死锁（过期可被接管），heartbeat 续期防长任务中途过期。
 */
@Getter
@Setter
@Entity
@Table(name = "flow_login_lock",
        uniqueConstraints = @UniqueConstraint(name = "uk_lock_key", columnNames = "lock_key"))
@Comment("办税员登录锁（按登录指纹全局互斥）")
public class FlowLoginLock implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Comment("锁键：办税员登录指纹（如 手机号 / 代理账号 / 身份证）")
    @Column(name = "lock_key", length = 128, nullable = false)
    private String lockKey;

    @Comment("占用者标识（进程/会话）")
    @Column(name = "owner", length = 100)
    private String owner;

    @Column(name = "claimed_at", nullable = false)
    private LocalDateTime claimedAt;

    @Comment("过期时间（claimed_at + TTL）；过期可接管，heartbeat 续期")
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
