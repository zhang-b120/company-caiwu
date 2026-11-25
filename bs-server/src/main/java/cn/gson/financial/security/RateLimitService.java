package cn.gson.financial.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * FN-SEC-T1（167）限流 / 防爆破 计数核心 —— 轻量 Redis 计数，<b>不引新框架</b>。
 *
 * <h3>可用性优先（降级放行）</h3>
 * .142 本机有 redis；但 redis 不可达时所有方法<b>放行 + warn</b>，
 * 绝不让安全功能变成可用性故障点把正常商家挡死（工单风险 3）。
 *
 * <h3>键约定</h3>
 * <ul>
 *   <li>固定窗口计数：{@code rl:<dim>:<key>}，首次 incr 设窗口 TTL。</li>
 *   <li>lookup 连续失败计数：{@code rl:lookup:fail:<ip>}。</li>
 *   <li>lookup 锁定标记：{@code rl:lookup:lock:<ip>}，TTL 到期即自动解锁。</li>
 * </ul>
 */
@Slf4j
@Service
public class RateLimitService {

    private static final String LOOKUP_FAIL_PREFIX = "rl:lookup:fail:";
    private static final String LOOKUP_LOCK_PREFIX = "rl:lookup:lock:";

    private final StringRedisTemplate redis;

    public RateLimitService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 固定窗口计数限流：在 {@code windowSeconds} 窗口内 {@code dimKey} 累计请求是否仍 ≤ {@code limit}。
     * <p>{@code limit ≤ 0} 视为该维度关闭（恒放行）。Redis 异常 → 放行（fail-open）+ warn。
     *
     * @return true=放行；false=超限应拦截。
     */
    public boolean allow(String dimKey, int limit, int windowSeconds) {
        if (limit <= 0) {
            return true;
        }
        try {
            Long count = redis.opsForValue().increment(dimKey);
            if (count == null) {
                return true;
            }
            if (count == 1L) {
                redis.expire(dimKey, Duration.ofSeconds(windowSeconds));
            } else if (count > limit) {
                // 防御：极端下首发 expire 丢失会让 key 永不过期、配额永久占满 → 兜底补一次 TTL。
                Long ttl = redis.getExpire(dimKey);
                if (ttl != null && ttl < 0) {
                    redis.expire(dimKey, Duration.ofSeconds(windowSeconds));
                }
            }
            return count <= limit;
        } catch (Exception ex) {
            log.warn("[SEC-RL] Redis 限流计数异常，降级放行（fail-open）：key={}, err={}", dimKey, ex.toString());
            return true;
        }
    }

    /** lookup 该 IP 是否处于锁定中（防撞库）。Redis 异常 → 视为未锁（fail-open）。 */
    public boolean isLookupLocked(String ip) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(LOOKUP_LOCK_PREFIX + ip));
        } catch (Exception ex) {
            log.warn("[SEC-RL] Redis 锁查询异常，降级放行（fail-open）：ip={}, err={}", ip, ex.toString());
            return false;
        }
    }

    /**
     * 记录 lookup 一次失败（连续失败计数 +1）；累计达 {@code threshold} 即锁该 IP {@code lockMinutes} 分钟，
     * 并清零计数（解锁后从头计），避免锁期叠加。
     *
     * @return 本次累计的连续失败数；Redis 异常时返回 0（不影响主流程）。
     */
    public long recordLookupFailure(String ip, int threshold, int lockMinutes) {
        try {
            String failKey = LOOKUP_FAIL_PREFIX + ip;
            Long count = redis.opsForValue().increment(failKey);
            if (count == null) {
                return 0;
            }
            if (count == 1L) {
                // 失败计数自身也给 TTL（= 锁定时长），避免零散失败永久累积
                redis.expire(failKey, Duration.ofMinutes(lockMinutes));
            }
            if (threshold > 0 && count >= threshold) {
                redis.opsForValue().set(LOOKUP_LOCK_PREFIX + ip, "1", Duration.ofMinutes(lockMinutes));
                redis.delete(failKey);
                log.warn("[SEC-RL] lookup 连续失败 {} 次达阈值，锁定 IP {} 分钟：ip={}", count, lockMinutes, ip);
            }
            return count;
        } catch (Exception ex) {
            log.warn("[SEC-RL] Redis 失败计数异常，忽略：ip={}, err={}", ip, ex.toString());
            return 0;
        }
    }

    /** lookup 成功 → 清零该 IP 的连续失败计数（"连续"语义）。 */
    public void clearLookupFailures(String ip) {
        try {
            redis.delete(LOOKUP_FAIL_PREFIX + ip);
        } catch (Exception ex) {
            log.warn("[SEC-RL] Redis 清失败计数异常，忽略：ip={}, err={}", ip, ex.toString());
        }
    }
}
