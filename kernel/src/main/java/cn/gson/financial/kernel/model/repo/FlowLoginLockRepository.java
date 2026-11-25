package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.FlowLoginLock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/** FN-LOGIN-LOCK-BLOCKING：办税员登录锁仓库。UNIQUE(lock_key)。 */
public interface FlowLoginLockRepository extends JpaRepository<FlowLoginLock, Long> {

    Optional<FlowLoginLock> findByLockKey(String lockKey);

    /**
     * 原子接管/续期（CAS）：仅当锁<b>已过期</b>或<b>本就是自己持有</b>时才改 owner+时间。
     * 影响行=1 → 抢到（接管过期 或 续自己的）；=0 → 行不存在 或 被他人有效持有。
     */
    @Modifying
    @Query("UPDATE FlowLoginLock l SET l.owner = :owner, l.claimedAt = :now, l.expiresAt = :exp "
            + "WHERE l.lockKey = :key AND (l.expiresAt < :now OR l.owner = :owner)")
    int takeoverOrRenew(@Param("key") String key, @Param("owner") String owner,
                        @Param("now") LocalDateTime now, @Param("exp") LocalDateTime exp);

    /** 续期（heartbeat）：仅持有者本人。影响行=1 → 续上；=0 → 已不是自己（丢锁/被接管）。 */
    @Modifying
    @Query("UPDATE FlowLoginLock l SET l.expiresAt = :exp WHERE l.lockKey = :key AND l.owner = :owner")
    int renewIfOwner(@Param("key") String key, @Param("owner") String owner, @Param("exp") LocalDateTime exp);

    /** 释放：仅持有者本人。 */
    @Modifying
    @Query("DELETE FROM FlowLoginLock l WHERE l.lockKey = :key AND l.owner = :owner")
    int deleteByKeyAndOwner(@Param("key") String key, @Param("owner") String owner);
}
