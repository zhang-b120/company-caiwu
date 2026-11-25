package cn.gson.financial.event;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * FN-05 推送失败死信队列。
 * <p>
 * Redis ZSET：key={@link #keyOf(String)}，score=最近重试时间戳（ms），value=失败明细 JSON。
 * <ul>
 *   <li>用 ZSET 不用 list — 方便按 score（lastRetryAt）扫，且去重</li>
 *   <li>TTL 7 天 — 超过 7 天没消费的告警 + 丢弃，避免无限堆积</li>
 *   <li>超过 24h 仍在队列里的 → worker 扫到时 warn log（潜在下游长期不可用）</li>
 * </ul>
 *
 * 失败 JSON schema：
 * <pre>
 * {
 *   "channel": "wx",
 *   "eventType": "declare_task_completed",
 *   "envelope": { ... },                   // 原 HTTP body
 *   "lastError": "Connection refused",
 *   "retryCount": 3,                       // 含首发，与 listener 的 attempts 一致
 *   "firstFailedAt": 1736588400000,
 *   "lastRetryAt": 1736588410000
 * }
 * </pre>
 */
@Slf4j
@Component
public class NotifyDeadLetterQueue {

    /** ZSET key 前缀，按 channel 拆分 → 失败聚合便于排查。 */
    public static final String KEY_PREFIX = "report:export:dlq:notify:";

    /** 7 天 TTL。 */
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;

    @Value(value = "${spring.cache.redis.key-prefix:}")
    private String keyPrefix = "";

    public NotifyDeadLetterQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 入队。 */
    public void enqueue(String channel, String eventType, Map<String, Object> envelope,
                        String lastError, int retryCount) {
        long now = System.currentTimeMillis();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("channel", channel);
        record.put("eventType", eventType);
        record.put("envelope", envelope);
        record.put("lastError", lastError);
        record.put("retryCount", retryCount);
        record.put("firstFailedAt", now);
        record.put("lastRetryAt", now);

        String json = JSON.toJSONString(record);
        String redisKey = fullKey(channel);
        redis.opsForZSet().add(redisKey, json, now);
        redis.expire(redisKey, TTL);
        log.warn("[FN-05] DLQ enqueue: channel={}, eventType={}, retryCount={}, lastError={}",
                channel, eventType, retryCount, lastError);
    }

    /**
     * 取最旧的 N 条（score 升序）。
     */
    public Set<ZSetOperations.TypedTuple<String>> peek(String channel, long limit) {
        return redis.opsForZSet().rangeWithScores(fullKey(channel), 0, limit - 1);
    }

    /**
     * 从 DLQ 移除某条记录（重发成功后调用）。
     */
    public void remove(String channel, String memberJson) {
        redis.opsForZSet().remove(fullKey(channel), memberJson);
    }

    /**
     * 更新某条记录的 lastRetryAt 并写回（重发失败后调用）。
     */
    public void updateRetry(String channel, String oldMemberJson, String lastError) {
        long now = System.currentTimeMillis();
        try {
            JSONObject obj = JSON.parseObject(oldMemberJson);
            obj.put("lastError", lastError);
            obj.put("retryCount", obj.getIntValue("retryCount") + 1);
            obj.put("lastRetryAt", now);
            String newJson = obj.toJSONString();
            String redisKey = fullKey(channel);
            redis.opsForZSet().remove(redisKey, oldMemberJson);
            redis.opsForZSet().add(redisKey, newJson, now);
            redis.expire(redisKey, TTL);
        } catch (Exception e) {
            // 不能解析就只刷 score
            redis.opsForZSet().add(fullKey(channel), oldMemberJson, now);
        }
    }

    /** 完整 Redis key（含 prefix）。 */
    public String fullKey(String channel) {
        return keyPrefix + keyOf(channel);
    }

    /** 不含 prefix 的 channel key（外部展示用）。 */
    public static String keyOf(String channel) {
        return KEY_PREFIX + channel;
    }
}
