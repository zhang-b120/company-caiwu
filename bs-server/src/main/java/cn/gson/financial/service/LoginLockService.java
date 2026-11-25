package cn.gson.financial.service;

import cn.gson.financial.kernel.model.entity.FlowLoginLock;
import cn.gson.financial.kernel.model.repo.FlowLoginLockRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * FN-LOGIN-LOCK-BLOCKING：办税员登录锁——阻塞抢锁 + 心跳续期。
 * <p>原子性靠 {@code flow_login_lock} 的 UNIQUE(lock_key) + 条件 UPDATE(CAS)：每次尝试独立事务
 * （{@link #tryAcquireOnce} REQUIRES_NEW），阻塞由 {@link #acquire} 轮询实现（拿不到 → 等到可用或超时）。
 * expires_at(TTL) 兜底防死锁（过期可接管）；heartbeat 续期防长任务中途过期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginLockService {

    private final FlowLoginLockRepository repo;

    /** 自注入：让 {@link #acquire} 轮询时每次 {@link #tryAcquireOnce} 走代理拿到独立事务。 */
    @Autowired
    @Lazy
    private LoginLockService self;

    public static final int DEFAULT_TTL_SECONDS = 600;   // 默认 10 分钟
    static final long POLL_MS = 300;                     // 轮询间隔
    static final long MAX_WAIT_MS = 60_000;              // 阻塞上限（防 HTTP 线程久占）

    /** 单次尝试（独立事务）：CAS 接管/续期，否则尝试插入；返回是否拿到。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryAcquireOnce(String lockKey, String owner, int ttlSeconds) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime exp = now.plusSeconds(ttlSeconds);
        // 1. 已存在：过期可接管 / 本就自己持有 → 续
        if (repo.takeoverOrRenew(lockKey, owner, now, exp) >= 1) return true;
        // 2. 行不存在 → 插入（UNIQUE 兜底并发双插）
        if (repo.findByLockKey(lockKey).isEmpty()) {
            try {
                FlowLoginLock l = new FlowLoginLock();
                l.setLockKey(lockKey);
                l.setOwner(owner);
                l.setClaimedAt(now);
                l.setExpiresAt(exp);
                repo.saveAndFlush(l);
                return true;
            } catch (DataIntegrityViolationException raced) {
                return false;   // 并发被别人先插
            }
        }
        // 3. 被他人有效持有
        return false;
    }

    /**
     * 阻塞抢锁：拿不到则轮询等待，直到拿到 或 超 waitMs（返 timeout）。waitMs=0 → 非阻塞单次。
     */
    public AcquireResult acquire(String lockKey, String owner, int ttlSeconds, long waitMs) {
        long capped = Math.min(Math.max(waitMs, 0), MAX_WAIT_MS);
        long deadline = System.currentTimeMillis() + capped;
        while (true) {
            if (self.tryAcquireOnce(lockKey, owner, ttlSeconds)) {
                FlowLoginLock l = repo.findByLockKey(lockKey).orElse(null);
                return AcquireResult.acquired(owner,
                        l != null ? l.getClaimedAt() : null, l != null ? l.getExpiresAt() : null);
            }
            if (System.currentTimeMillis() >= deadline) break;
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 超时 → 返当前持有者信息
        FlowLoginLock cur = repo.findByLockKey(lockKey).orElse(null);
        return AcquireResult.timeout(cur != null ? cur.getOwner() : null,
                cur != null ? cur.getClaimedAt() : null, cur != null ? cur.getExpiresAt() : null);
    }

    /** 心跳续期：仅持有者本人。返回新的 expires_at；丢锁返 null。 */
    @Transactional
    public LocalDateTime heartbeat(String lockKey, String owner, int ttlSeconds) {
        LocalDateTime exp = LocalDateTime.now().plusSeconds(ttlSeconds);
        return repo.renewIfOwner(lockKey, owner, exp) >= 1 ? exp : null;
    }

    /** 释放（仅持有者本人）。无行/已释放视为成功。 */
    @Transactional
    public boolean release(String lockKey, String owner) {
        repo.deleteByKeyAndOwner(lockKey, owner);
        return true;
    }

    @Getter
    public static class AcquireResult {
        private final boolean acquired;
        private final boolean timeout;
        private final String owner;            // acquired=自己；timeout=当前持有者
        private final LocalDateTime claimedAt;
        private final LocalDateTime expiresAt;

        private AcquireResult(boolean acquired, boolean timeout, String owner,
                              LocalDateTime claimedAt, LocalDateTime expiresAt) {
            this.acquired = acquired;
            this.timeout = timeout;
            this.owner = owner;
            this.claimedAt = claimedAt;
            this.expiresAt = expiresAt;
        }

        static AcquireResult acquired(String owner, LocalDateTime claimedAt, LocalDateTime expiresAt) {
            return new AcquireResult(true, false, owner, claimedAt, expiresAt);
        }

        static AcquireResult timeout(String owner, LocalDateTime claimedAt, LocalDateTime expiresAt) {
            return new AcquireResult(false, true, owner, claimedAt, expiresAt);
        }
    }
}
