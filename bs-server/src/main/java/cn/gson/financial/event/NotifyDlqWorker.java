package cn.gson.financial.event;

import cn.gson.financial.kernel.model.entity.InvoiceTask;
import cn.gson.financial.kernel.model.repo.InvoiceTaskRepository;
import cn.gson.financial.kernel.service.InvoiceNotificationService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * FN-05 DLQ 后台扫描 — 5 分钟一次。
 * <p>
 * 对每个 channel（wx / wecom / finmaster / applicant / applicant-reject / applicant-red-letter）
 * 扫最旧的 N 条，按 lastRetryAt &lt; now-冷却期 的尝试重发：
 * <ul>
 *   <li>成功 → 从 DLQ 移除，metrics +1 success（带 source=dlq tag）</li>
 *   <li>失败 → updateRetry 写回（lastRetryAt=now, retryCount++）</li>
 *   <li>4xx → 直接丢弃 + warn log（业务永远拒绝）</li>
 *   <li>firstFailedAt 超过 24h 仍在队列 → 每次扫到都 warn alarm（通用通道仍只 warn 不丢）</li>
 * </ul>
 * 冷却期保证不和 listener 本身 3 次指数退避抢资源。
 * <p>
 * <b>FN9-T1 升级（用户级通知体验）</b>：
 * <ul>
 *   <li>快重试 {@link #triggerFastRetry(String)}：用户级通道入队后立即异步扫一次（绕过冷却期），不等 5 分钟周期。</li>
 *   <li>短 TTL：{@code applicant-reject} / {@code applicant-red-letter} 超过短 TTL（默认 4h，
 *       {@code app.notify.dlq.ttl-ms.<channel>} 可覆盖）即标 dead 丢弃；wx/wecom/finmaster/applicant 仍用通用 24h warn-only。</li>
 *   <li>{@code applicant}（CONFIRM）补上消费者 {@link #handleApplicant}（带 notified_at 幂等），
 *       此前该通道入队后从不被消费（仅靠 7 天 Redis TTL 过期）。</li>
 * </ul>
 *
 * @see TaskNotificationListener
 * @see NotifyDeadLetterQueue
 */
@Slf4j
@Component
public class NotifyDlqWorker {

    /** 每次扫描每 channel 取的最大条数，避免一次扫太多。 */
    private static final int BATCH = 50;

    /** DLQ 内每条记录的冷却期 — 至少 5 分钟内不重发同一条。 */
    private static final long COOLDOWN_MS = Duration.ofMinutes(5).toMillis();

    /** 24h 仍未消费 → 报警（通用阈值；wx/wecom/finmaster/applicant 仍只 warn 不丢）。 */
    private static final long ALARM_AGE_MS = Duration.ofHours(24).toMillis();

    /** FN-T4：applicant（CONFIRM/SUCCESS）申请人通知，由 listener 入队，走 internal service.notifyApplicant */
    static final String CHANNEL_APPLICANT = "applicant";
    /** FN7-T1：applicant-reject 与 wx/wecom/finmaster 并列。是 internal service 调用，不走 HTTP push */
    static final String CHANNEL_APPLICANT_REJECT = "applicant-reject";
    /** FN8-T2：applicant-red-letter，与 applicant-reject 同结构，走 internal service */
    static final String CHANNEL_APPLICANT_RED_LETTER = "applicant-red-letter";

    private static final String[] CHANNELS = {"wx", "wecom", "finmaster",
            CHANNEL_APPLICANT, CHANNEL_APPLICANT_REJECT, CHANNEL_APPLICANT_RED_LETTER};

    private final NotifyDeadLetterQueue dlq;
    private final RestClient restClient = RestClient.builder()
            .requestFactory(buildRequestFactory())
            .build();

    @Value("${app.notify.wx-push-url:}")
    private String wxPushUrl;

    @Value("${app.notify.wecom-push-url:}")
    private String wecomPushUrl;

    @Value("${app.notify.finmaster-task-url:}")
    private String finmasterTaskUrl;

    /**
     * FN9-T1：applicant-reject 的短 dead-letter TTL（ms）。超过即标 dead 丢弃，不再重试。
     * 默认 4h，与通用 24h 区分；置 0 关闭短 TTL（回落通用 24h warn-only）。
     */
    @Value("${app.notify.dlq.ttl-ms.applicant-reject:14400000}")
    private long ttlApplicantRejectMs;

    /** FN9-T1：applicant-red-letter 的短 dead-letter TTL（ms），默认 4h；置 0 关闭。 */
    @Value("${app.notify.dlq.ttl-ms.applicant-red-letter:14400000}")
    private long ttlApplicantRedLetterMs;

    /** FN7-T1：applicant-reject 通道重试时调用此 service.notifyRejected；required=false 兼容测试 */
    @Autowired(required = false)
    private InvoiceNotificationService invoiceNotificationService;

    /** FN9-T1：applicant（CONFIRM）通道重试时做 notified_at 幂等检查；required=false 兼容测试 */
    @Autowired(required = false)
    private InvoiceTaskRepository invoiceTaskRepository;

    public NotifyDlqWorker(NotifyDeadLetterQueue dlq) {
        this.dlq = dlq;
    }

    /** FN7-T1 测试 hook：直接注入 service mock，避免起完整 ApplicationContext */
    void setInvoiceNotificationService(InvoiceNotificationService svc) {
        this.invoiceNotificationService = svc;
    }

    /** FN9-T1 测试 hook：注入 InvoiceTaskRepository mock（applicant 幂等检查用） */
    void setInvoiceTaskRepository(InvoiceTaskRepository repo) {
        this.invoiceTaskRepository = repo;
    }

    private static org.springframework.http.client.SimpleClientHttpRequestFactory buildRequestFactory() {
        org.springframework.http.client.SimpleClientHttpRequestFactory f =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        f.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        return f;
    }

    /**
     * 每 5 分钟扫一次 — fixedDelay 等上一次跑完，避免堆积。
     */
    @Scheduled(fixedDelayString = "${app.notify.dlq.scan-interval-ms:300000}",
            initialDelayString = "${app.notify.dlq.scan-initial-delay-ms:60000}")
    public void scan() {
        for (String channel : CHANNELS) {
            try {
                scanChannel(channel);
            } catch (Exception ex) {
                log.warn("[FN-05] DLQ scan error: channel={}, err={}", channel, ex.getMessage(), ex);
            }
        }
    }

    /**
     * 单 channel 扫描（周期扫描入口，遵守冷却期）— public 让跨包测试（cn.gson.financial.integration）也能调用。
     */
    public int scanChannel(String channel) {
        return scanChannel(channel, false);
    }

    /**
     * 单 channel 扫描。
     *
     * @param ignoreCooldown true 时绕过 5 分钟冷却期，对队列里所有记录立即尝试一次
     *                       —— FN9-T1 快重试用：刚入队记录 score=now，正常扫描会因冷却期跳过，
     *                       快重试必须绕过冷却期才能立刻补发一次。
     */
    public int scanChannel(String channel, boolean ignoreCooldown) {
        String url = urlFor(channel);
        Set<ZSetOperations.TypedTuple<String>> tuples = dlq.peek(channel, BATCH);
        if (tuples == null || tuples.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int processed = 0;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String json = tuple.getValue();
            Double score = tuple.getScore();
            if (json == null) continue;
            // 冷却期内跳过（快重试 ignoreCooldown=true 时不跳）
            if (!ignoreCooldown && score != null && (now - score.longValue()) < COOLDOWN_MS) {
                continue;
            }
            processed += handleOne(channel, url, json, now);
        }
        return processed;
    }

    /**
     * FN9-T1 快重试：用户级通知（applicant / applicant-reject / applicant-red-letter）入队后，
     * 立即异步触发一次对该 channel 的扫描（绕过冷却期），不等 5 分钟周期，降低用户体感延迟。
     * <p>
     * fire-and-forget：自身永不抛异常（已在失败兜底路径上，再挂只 log）。
     * wx/wecom/finmaster 不调用此方法，维持原 5 分钟周期。
     */
    @Async
    public void triggerFastRetry(String channel) {
        try {
            int processed = scanChannel(channel, true);
            log.info("[FN9-T1] fast retry triggered: channel={}, processed={}", channel, processed);
        } catch (Exception ex) {
            log.warn("[FN9-T1] fast retry error: channel={}, err={}", channel, ex.getMessage(), ex);
        }
    }

    private int handleOne(String channel, String url, String json, long now) {
        JSONObject record;
        try {
            record = JSON.parseObject(json);
        } catch (Exception e) {
            log.warn("[FN-05] DLQ record parse fail, dropping: channel={}, err={}, raw={}", channel, e.getMessage(), json);
            dlq.remove(channel, json);
            return 1;
        }

        long firstFailedAt = record.getLongValue("firstFailedAt");

        // FN9-T1：用户级通道短 TTL —— 超过即标 dead 丢弃，不再重试。
        // 仅对显式配了短 TTL 的通道（applicant-reject / applicant-red-letter）生效；
        // wx/wecom/finmaster/applicant 短 TTL=null → 不受影响，仍走下方通用 24h warn-only。
        Long shortTtl = shortTtlMsFor(channel);
        if (shortTtl != null && firstFailedAt > 0 && (now - firstFailedAt) > shortTtl) {
            log.warn("[FN9-T1][DEAD] DLQ record exceeded short TTL, dropping: channel={}, eventType={}, ageMs={}, ttlMs={}, retryCount={}",
                    channel, record.getString("eventType"), now - firstFailedAt, shortTtl, record.getIntValue("retryCount"));
            dlq.remove(channel, json);
            NotifyMetrics.failure(channel + ":dlq", "dead_short_ttl", record.getIntValue("retryCount"), 0L);
            return 1;
        }

        if (firstFailedAt > 0 && (now - firstFailedAt) > ALARM_AGE_MS) {
            log.warn("[FN-05][ALARM] DLQ record older than 24h, downstream likely down: channel={}, eventType={}, firstFailedAt={}, retryCount={}",
                    channel, record.getString("eventType"), firstFailedAt, record.getIntValue("retryCount"));
            // 继续尝试，不丢
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) record.get("envelope");
        if (envelope == null) {
            log.warn("[FN-05] DLQ record missing envelope, dropping: channel={}", channel);
            dlq.remove(channel, json);
            return 1;
        }

        // FN-T4：applicant（CONFIRM/SUCCESS）走 internal service.notifyApplicant（带 notified_at 幂等）
        if (CHANNEL_APPLICANT.equals(channel)) {
            return handleApplicant(channel, record, envelope, json);
        }
        // FN7-T1：applicant-reject 走 internal service.notifyRejected，不走 HTTP push
        if (CHANNEL_APPLICANT_REJECT.equals(channel)) {
            return handleApplicantReject(channel, record, envelope, json);
        }
        // FN8-T2：applicant-red-letter 走 internal service.notifyRedLetterIssued
        if (CHANNEL_APPLICANT_RED_LETTER.equals(channel)) {
            return handleApplicantRedLetter(channel, record, envelope, json);
        }

        // URL 真空 → 跳过（NO-OP，等配置就绪）
        if (url == null || url.isBlank()) {
            log.debug("[FN-05] DLQ skip {}: url not configured, eventType={}", channel, record.getString("eventType"));
            return 0;
        }

        try {
            doPost(url, envelope);
            // 成功 → 从 DLQ 移除
            dlq.remove(channel, json);
            NotifyMetrics.success(channel + ":dlq", record.getIntValue("retryCount") + 1, 0L);
            log.info("[FN-05] DLQ resend success: channel={}, eventType={}", channel, record.getString("eventType"));
            return 1;
        } catch (RestClientResponseException ex) {
            int code = ex.getStatusCode().value();
            if (code >= 400 && code < 500) {
                // 4xx 永远拒绝，丢弃
                log.warn("[FN-05] DLQ resend 4xx, dropping: channel={}, eventType={}, status={}, body={}",
                        channel, record.getString("eventType"), code, ex.getResponseBodyAsString());
                dlq.remove(channel, json);
                NotifyMetrics.failure(channel + ":dlq", "http_" + code, record.getIntValue("retryCount") + 1, 0L);
                return 1;
            }
            // 5xx — 留下，更新 lastRetryAt
            dlq.updateRetry(channel, json, "HTTP " + code);
            NotifyMetrics.failure(channel + ":dlq", "http_" + code, record.getIntValue("retryCount") + 1, 0L);
            return 1;
        } catch (Exception ex) {
            dlq.updateRetry(channel, json, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            NotifyMetrics.failure(channel + ":dlq", ex.getClass().getSimpleName(), record.getIntValue("retryCount") + 1, 0L);
            return 1;
        }
    }

    /** 包级可见方便测试 spy。 */
    void doPost(String url, Map<String, Object> envelope) {
        restClient.post()
                .uri(url)
                .body(envelope)
                .retrieve()
                .toBodilessEntity();
    }

    private String urlFor(String channel) {
        return switch (channel) {
            case "wx" -> wxPushUrl;
            case "wecom" -> wecomPushUrl;
            case "finmaster" -> finmasterTaskUrl;
            // applicant / applicant-reject / applicant-red-letter 走 internal service 不需要 URL；非 null/blank 让 handleOne 不要 NO-OP
            case CHANNEL_APPLICANT -> "internal://applicant";
            case CHANNEL_APPLICANT_REJECT -> "internal://applicant-reject";
            case CHANNEL_APPLICANT_RED_LETTER -> "internal://applicant-red-letter";
            default -> null;
        };
    }

    /** FN9-T1：返回某 channel 的短 dead-letter TTL（ms）；未配短 TTL 返回 null（回落通用 24h warn-only）。 */
    private Long shortTtlMsFor(String channel) {
        return switch (channel) {
            case CHANNEL_APPLICANT_REJECT -> ttlApplicantRejectMs > 0 ? ttlApplicantRejectMs : null;
            case CHANNEL_APPLICANT_RED_LETTER -> ttlApplicantRedLetterMs > 0 ? ttlApplicantRedLetterMs : null;
            default -> null;   // applicant(CONFIRM) / wx / wecom / finmaster 不设短 TTL
        };
    }

    /**
     * FN-T4 / FN9-T1：处理 applicant（CONFIRM/SUCCESS 申请人通知）通道。
     * <p>
     * 该通道原由 {@code TaskNotificationListener.notifyApplicantSafely} 入队，但此前 worker
     * 既未纳入 CHANNELS、也无 handler，记录入队后只能等 7 天 Redis TTL 过期 —— 等于从不重试。
     * 本期补上消费者；为避免重复发，带 {@code notified_at} 幂等：
     * <ul>
     *   <li>task 已 notified（notified_at 非空）→ 视为别处已补发成功，移除不再发</li>
     *   <li>notifyApplicant 成功 → markNotified + 移除 + metrics</li>
     *   <li>IllegalArgumentException（task 不存在）→ 丢弃</li>
     *   <li>其他 RuntimeException（NotificationException 等）→ 留下重试 updateRetry</li>
     * </ul>
     */
    int handleApplicant(String channel, JSONObject record, Map<String, Object> envelope, String json) {
        if (invoiceNotificationService == null) {
            log.debug("[FN-T4] DLQ skip {}: InvoiceNotificationService 未注入（测试环境）", channel);
            return 0;
        }
        Object taskIdObj = envelope.get("taskId");
        if (taskIdObj == null) {
            log.warn("[FN-T4] DLQ applicant record missing taskId, dropping");
            dlq.remove(channel, json);
            return 1;
        }
        long taskId;
        try {
            taskId = Long.parseLong(taskIdObj.toString());
        } catch (NumberFormatException e) {
            log.warn("[FN-T4] DLQ applicant taskId 非法 {}: {}, dropping", taskIdObj, e.getMessage());
            dlq.remove(channel, json);
            return 1;
        }

        // 幂等：已通知过的任务不重发（notified_at 由成功路径 markNotified 写入）
        if (invoiceTaskRepository != null) {
            InvoiceTask t = invoiceTaskRepository.findById(taskId).orElse(null);
            if (t != null && t.getNotifiedAt() != null) {
                log.info("[FN-T4] DLQ applicant 已通知过(notified_at={})，移除不重发: taskId={}", t.getNotifiedAt(), taskId);
                dlq.remove(channel, json);
                return 1;
            }
        }

        try {
            invoiceNotificationService.notifyApplicant(taskId);
            invoiceNotificationService.markNotified(taskId);
            dlq.remove(channel, json);
            NotifyMetrics.success(channel + ":dlq", record.getIntValue("retryCount") + 1, 0L);
            log.info("[FN-T4] DLQ resend applicant success: taskId={}", taskId);
            return 1;
        } catch (IllegalArgumentException ex) {
            log.warn("[FN-T4] DLQ applicant illegal arg, dropping: taskId={}, err={}", taskId, ex.getMessage());
            dlq.remove(channel, json);
            NotifyMetrics.failure(channel + ":dlq", "illegal_arg", record.getIntValue("retryCount") + 1, 0L);
            return 1;
        } catch (RuntimeException ex) {
            dlq.updateRetry(channel, json, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            NotifyMetrics.failure(channel + ":dlq", ex.getClass().getSimpleName(),
                    record.getIntValue("retryCount") + 1, 0L);
            return 1;
        }
    }

    /**
     * FN7-T1：处理 applicant-reject 通道。
     * <ul>
     *   <li>调 InvoiceNotificationService.notifyRejected(taskId, comment)</li>
     *   <li>成功 → DLQ 移除 + metrics +1</li>
     *   <li>IllegalArgumentException（task 不存在）→ 业务永远拒，丢弃</li>
     *   <li>NotificationException 等 → 留下重试 updateRetry</li>
     * </ul>
     */
    int handleApplicantReject(String channel, JSONObject record, Map<String, Object> envelope, String json) {
        if (invoiceNotificationService == null) {
            log.debug("[FN-05] DLQ skip {}: InvoiceNotificationService 未注入（测试环境）", channel);
            return 0;
        }
        Object taskIdObj = envelope.get("taskId");
        if (taskIdObj == null) {
            log.warn("[FN-05] DLQ applicant-reject record missing taskId, dropping");
            dlq.remove(channel, json);
            return 1;
        }
        long taskId;
        try {
            taskId = Long.parseLong(taskIdObj.toString());
        } catch (NumberFormatException e) {
            log.warn("[FN-05] DLQ applicant-reject taskId 非法 {}: {}, dropping", taskIdObj, e.getMessage());
            dlq.remove(channel, json);
            return 1;
        }
        String comment = envelope.get("comment") == null ? null : envelope.get("comment").toString();

        try {
            invoiceNotificationService.notifyRejected(taskId, comment);
            dlq.remove(channel, json);
            NotifyMetrics.success(channel + ":dlq", record.getIntValue("retryCount") + 1, 0L);
            log.info("[FN-05] DLQ resend applicant-reject success: taskId={}", taskId);
            return 1;
        } catch (IllegalArgumentException ex) {
            // task 不存在等业务永久拒，丢弃
            log.warn("[FN-05] DLQ applicant-reject illegal arg, dropping: taskId={}, err={}",
                    taskId, ex.getMessage());
            dlq.remove(channel, json);
            NotifyMetrics.failure(channel + ":dlq", "illegal_arg", record.getIntValue("retryCount") + 1, 0L);
            return 1;
        } catch (RuntimeException ex) {
            // NotificationException 等其他 RuntimeException → 留下重试
            dlq.updateRetry(channel, json, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            NotifyMetrics.failure(channel + ":dlq", ex.getClass().getSimpleName(),
                    record.getIntValue("retryCount") + 1, 0L);
            return 1;
        }
    }

    /**
     * FN8-T2：处理 applicant-red-letter 通道。
     * <ul>
     *   <li>调 InvoiceNotificationService.notifyRedLetterIssued(taskId)</li>
     *   <li>成功 → DLQ remove + metrics</li>
     *   <li>IllegalArgumentException → 4xx 丢弃</li>
     *   <li>其他 RuntimeException → 留下重试</li>
     * </ul>
     * <p>envelope 只含 taskId（红冲号码 / PDF URL 从 entity 加载）。
     */
    int handleApplicantRedLetter(String channel, JSONObject record, Map<String, Object> envelope, String json) {
        if (invoiceNotificationService == null) {
            log.debug("[FN-05] DLQ skip {}: InvoiceNotificationService 未注入（测试环境）", channel);
            return 0;
        }
        Object taskIdObj = envelope.get("taskId");
        if (taskIdObj == null) {
            log.warn("[FN-05] DLQ applicant-red-letter record missing taskId, dropping");
            dlq.remove(channel, json);
            return 1;
        }
        long taskId;
        try {
            taskId = Long.parseLong(taskIdObj.toString());
        } catch (NumberFormatException e) {
            log.warn("[FN-05] DLQ applicant-red-letter taskId 非法 {}: {}, dropping", taskIdObj, e.getMessage());
            dlq.remove(channel, json);
            return 1;
        }

        try {
            invoiceNotificationService.notifyRedLetterIssued(taskId);
            dlq.remove(channel, json);
            NotifyMetrics.success(channel + ":dlq", record.getIntValue("retryCount") + 1, 0L);
            log.info("[FN-05] DLQ resend applicant-red-letter success: taskId={}", taskId);
            return 1;
        } catch (IllegalArgumentException ex) {
            log.warn("[FN-05] DLQ applicant-red-letter illegal arg, dropping: taskId={}, err={}",
                    taskId, ex.getMessage());
            dlq.remove(channel, json);
            NotifyMetrics.failure(channel + ":dlq", "illegal_arg", record.getIntValue("retryCount") + 1, 0L);
            return 1;
        } catch (RuntimeException ex) {
            dlq.updateRetry(channel, json, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            NotifyMetrics.failure(channel + ":dlq", ex.getClass().getSimpleName(),
                    record.getIntValue("retryCount") + 1, 0L);
            return 1;
        }
    }
}
