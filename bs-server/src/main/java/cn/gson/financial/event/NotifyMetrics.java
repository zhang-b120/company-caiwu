package cn.gson.financial.event;

import lombok.extern.slf4j.Slf4j;

/**
 * FN-05 推送监控 — log stub 实现。
 * <p>
 * 项目未引 Micrometer / Actuator，避免新增依赖：
 * <ul>
 *   <li>{@code financial.notify.push.success} → {@code [METRIC] financial.notify.push.success channel=X attempts=N duration_ms=K}</li>
 *   <li>{@code financial.notify.push.failure} → {@code [METRIC] financial.notify.push.failure channel=X reason=R attempts=N duration_ms=K}</li>
 *   <li>{@code financial.notify.push.duration} → 上面两条都已带 duration_ms</li>
 * </ul>
 * 后续接入 Prometheus / Loki 可正则抽取 [METRIC] 行。
 * <p>
 * 留 {@link #incSuccessCount(String)} / {@link #incFailureCount(String)} 静态计数器
 * 给测试断言用（不要在业务代码里依赖这两个 getter）。
 */
@Slf4j
public final class NotifyMetrics {

    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder>
            SUCCESS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder>
            FAILURE = new java.util.concurrent.ConcurrentHashMap<>();

    private NotifyMetrics() {}

    public static void success(String channel, int attempts, long durationMs) {
        incSuccessCount(channel);
        log.info("[METRIC] financial.notify.push.success channel={} attempts={} duration_ms={}",
                channel, attempts, durationMs);
    }

    public static void failure(String channel, String reason, int attempts, long durationMs) {
        incFailureCount(channel);
        log.info("[METRIC] financial.notify.push.failure channel={} reason={} attempts={} duration_ms={}",
                channel, reason, attempts, durationMs);
    }

    /** 仅供测试断言用。 */
    public static long getSuccessCount(String channel) {
        java.util.concurrent.atomic.LongAdder a = SUCCESS.get(channel);
        return a == null ? 0L : a.sum();
    }

    /** 仅供测试断言用。 */
    public static long getFailureCount(String channel) {
        java.util.concurrent.atomic.LongAdder a = FAILURE.get(channel);
        return a == null ? 0L : a.sum();
    }

    /** 仅供测试 setUp 调用。 */
    public static void resetForTest() {
        SUCCESS.clear();
        FAILURE.clear();
    }

    private static void incSuccessCount(String channel) {
        SUCCESS.computeIfAbsent(channel, k -> new java.util.concurrent.atomic.LongAdder()).increment();
    }

    private static void incFailureCount(String channel) {
        FAILURE.computeIfAbsent(channel, k -> new java.util.concurrent.atomic.LongAdder()).increment();
    }
}
