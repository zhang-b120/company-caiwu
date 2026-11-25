package cn.gson.financial.service;

import cn.gson.financial.kernel.model.entity.FlowClaim;
import cn.gson.financial.kernel.model.entity.FlowPeriod;
import cn.gson.financial.kernel.model.repo.FlowClaimRepository;
import cn.gson.financial.kernel.model.repo.FlowPeriodRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * FN-FLOW-CLAIM：跨进程执行锁 / 登录锁。复用服务器 full-flow 已有的 {@code flow_period} 悲观行锁认领，
 * 扩成通用 claim/release（桌面批量 + 服务器自动 共用）。
 * <p>抢锁：先 {@link FlowService#getOrCreatePeriod} 保证主轴行存在，再对该行加 PESSIMISTIC_WRITE 锁串行化，
 * 锁内查同 (account_set_id, period, stage) 的锁行——有效占用 → 占用中；无 / 已过期(TTL) / 同 owner → (重)抢。
 * UNIQUE(account_set_id,period,stage) 兜底防并发双插；expires_at 兜底防持有者崩溃死锁。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowClaimService {

    private final FlowClaimRepository claimRepo;
    private final FlowPeriodRepository flowPeriodRepository;
    private final FlowService flowService;

    /** 默认锁 TTL（秒）：30 分钟，覆盖一次全流程 RPA 时长。 */
    public static final int DEFAULT_TTL_SECONDS = 1800;

    @Transactional
    public ClaimResult claim(Long accountSetId, String period, String stage, String owner, Integer ttlSeconds) {
        int ttl = (ttlSeconds == null || ttlSeconds <= 0) ? DEFAULT_TTL_SECONDS : ttlSeconds;

        // 串行化：保证主轴行存在并加行级写锁（与 full-flow 认领同口径）
        FlowPeriod fp = flowService.getOrCreatePeriod(accountSetId, period);
        flowPeriodRepository.findByIdForUpdate(fp.getId());

        LocalDateTime now = LocalDateTime.now();
        FlowClaim c = claimRepo.findByAccountSetIdAndPeriodAndStage(accountSetId, period, stage).orElse(null);

        // 他人持有且未过期 → 占用中
        if (c != null && c.getExpiresAt() != null && now.isBefore(c.getExpiresAt())
                && !Objects.equals(owner, c.getOwner())) {
            return ClaimResult.occupied(c.getOwner(), c.getClaimedAt(), c.getExpiresAt());
        }

        // 无 / 已过期(可接管) / 同 owner(重入续期) → 抢到
        boolean takenOver = c != null && (c.getExpiresAt() == null || !now.isBefore(c.getExpiresAt()))
                && !Objects.equals(owner, c.getOwner());
        if (c == null) {
            c = new FlowClaim();
            c.setAccountSetId(accountSetId);
            c.setPeriod(period);
            c.setStage(stage);
        }
        c.setOwner(owner);
        c.setClaimedAt(now);
        c.setExpiresAt(now.plusSeconds(ttl));
        claimRepo.save(c);
        if (takenOver) log.info("[FN-FLOW-CLAIM] 过期接管 acc={} period={} stage={} newOwner={}", accountSetId, period, stage, owner);
        return ClaimResult.ok(owner, c.getClaimedAt(), c.getExpiresAt());
    }

    /** 释放：仅 owner 本人可释放（owner 传 null 视为强制释放）。返回是否已释放。 */
    @Transactional
    public boolean release(Long accountSetId, String period, String stage, String owner) {
        FlowClaim c = claimRepo.findByAccountSetIdAndPeriodAndStage(accountSetId, period, stage).orElse(null);
        if (c == null) return true;   // 本就空闲
        if (owner != null && !owner.equals(c.getOwner())) {
            return false;             // 非持有者，不许释放
        }
        claimRepo.delete(c);
        return true;
    }

    @Getter
    public static class ClaimResult {
        private final boolean ok;          // 是否拿到锁
        private final String owner;        // 当前占用者（拿到=自己；占用中=他人）
        private final LocalDateTime claimedAt;
        private final LocalDateTime expiresAt;

        private ClaimResult(boolean ok, String owner, LocalDateTime claimedAt, LocalDateTime expiresAt) {
            this.ok = ok;
            this.owner = owner;
            this.claimedAt = claimedAt;
            this.expiresAt = expiresAt;
        }

        static ClaimResult ok(String owner, LocalDateTime claimedAt, LocalDateTime expiresAt) {
            return new ClaimResult(true, owner, claimedAt, expiresAt);
        }

        static ClaimResult occupied(String owner, LocalDateTime claimedAt, LocalDateTime expiresAt) {
            return new ClaimResult(false, owner, claimedAt, expiresAt);
        }
    }
}
