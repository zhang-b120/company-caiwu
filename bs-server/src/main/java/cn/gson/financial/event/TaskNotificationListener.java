package cn.gson.financial.event;

import cn.gson.financial.kernel.event.CustomerGroupChangedEvent;
import cn.gson.financial.kernel.event.DeclareTaskStatusChangedEvent;
import cn.gson.financial.kernel.event.InvoiceTaskCompletedEvent;
import cn.gson.financial.kernel.model.entity.InvoiceTask;
import cn.gson.financial.kernel.model.enums.DeclareTaskStatus;
import cn.gson.financial.kernel.service.InvoiceNotificationService;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FN-05 事件总线 — 任务状态推送监听器
 * <p>
 * 三个 @Async 监听方法，分别推送到 wx / 企微 / finmaster：
 *   - {@link #onDeclareTaskChanged}     —— 申报任务每次状态变更
 *   - {@link #onInvoiceTaskCompleted}   —— 开票任务终态（SUCCESS/FAILED/RED_PENDING）
 *   - {@link #onCustomerGroupChanged}   —— 客户群路由变更（WC 缓存失效）
 * <p>
 * 所有方法 @Async 不阻塞主流程。
 * <p>
 * <b>FN-FIX-03 升级</b>：
 * <ul>
 *   <li>URL 默认占位 PREP-016 内部域名（{@code wx-push.internal} 等）；配置真空才走 NO-OP</li>
 *   <li>5xx / IOException / Timeout → 3 次指数退避重试（500ms / 1.5s / 3s）</li>
 *   <li>4xx → 业务拒绝，立即入 DLQ 不重试</li>
 *   <li>最终失败 → Redis ZSET DLQ（{@code report:export:dlq:notify:{channel}}），由 {@link NotifyDlqWorker} 5 分钟扫一次重试</li>
 *   <li>监控用 log stub（{@code [METRIC]} 前缀），项目未引 micrometer，避免新增依赖</li>
 * </ul>
 *
 * @see cn.gson.financial.FinancialApplication 已加 {@code @EnableAsync} + {@code @EnableScheduling}
 * @see NotifyDlqWorker DLQ 定时扫描重试
 */
@Slf4j
@Component
public class TaskNotificationListener {

    /**
     * 下游推送基址 — 三个团队的服务地址。
     * <p>配置真空（null/blank）时走 NO-OP；否则真发 HTTP（带重试 + DLQ）。
     */
    @Value("${app.notify.wx-push-url:}")
    private String wxPushUrl;

    @Value("${app.notify.wecom-push-url:}")
    private String wecomPushUrl;

    @Value("${app.notify.finmaster-task-url:}")
    private String finmasterTaskUrl;

    /**
     * 最大重试次数（不含首发）。
     */
    @Value("${app.notify.max-retry:3}")
    private int maxRetry;

    /**
     * RestClient 单例，5s connect / 10s read。
     */
    private final RestClient restClient;

    /**
     * DLQ — 失败后入队，由 worker 扫描重发。
     * <p>测试时可不注入（spy 直接打桩 push*，不走真发路径）。
     */
    @Autowired(required = false)
    private NotifyDeadLetterQueue dlq;

    /**
     * FN9-T1：applicant 通知入队后立即触发一次快重试（绕过 5 分钟冷却期）；required=false 兼容测试。
     */
    @Autowired(required = false)
    private NotifyDlqWorker notifyDlqWorker;

    /**
     * FN-T4 申请人通知（短信+邮件）服务。
     * <p>SUCCESS 分支额外发申请人通知；与 /notify 端点共用。
     */
    @Autowired(required = false)
    private InvoiceNotificationService invoiceNotificationService;

    public TaskNotificationListener() {
        this.restClient = RestClient.builder()
                .requestFactory(buildRequestFactory())
                .build();
    }

    private static org.springframework.http.client.SimpleClientHttpRequestFactory buildRequestFactory() {
        org.springframework.http.client.SimpleClientHttpRequestFactory f =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        f.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        return f;
    }

    // ═══════════════════════════════════════════════════════════════
    // 申报任务事件 — 每次 transition 都推
    // ═══════════════════════════════════════════════════════════════

    /**
     * 申报任务状态变更：根据状态决定推哪几个下游。
     */
    @Async
    @EventListener
    public void onDeclareTaskChanged(DeclareTaskStatusChangedEvent ev) {
        log.info("[FN-05] declare-task event: id={}, {} → {}, executor={}, terminal={}",
                ev.getTaskId(), ev.getFromStatus(), ev.getToStatus(),
                ev.getExecutor(), ev.isTerminal());

        Map<String, Object> payload = baseDeclarePayload(ev);

        // 企微：所有 transition 都推
        pushToWecom("declare_task_status_changed", payload);

        // 终态时推 wx
        if (ev.isTerminal()) {
            pushToWx("declare_task_completed", payload);
        }

        // FAILED/MANUAL → finmaster 建跟进工单
        if (ev.getToStatus() == DeclareTaskStatus.FAILED
                || ev.getToStatus() == DeclareTaskStatus.MANUAL) {
            pushToFinmaster("declare_task_followup", payload);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 开票任务完成事件
    // ═══════════════════════════════════════════════════════════════

    @Async
    @EventListener
    public void onInvoiceTaskCompleted(InvoiceTaskCompletedEvent ev) {
        log.info("[FN-05] invoice-task completed: id={}, status={}, isRedInvoice={}, code={}, number={}",
                ev.getTaskId(), ev.getFinalStatus(), ev.isRedInvoice(),
                ev.getInvoiceCode(), ev.getInvoiceNumber());

        Map<String, Object> payload = baseInvoicePayload(ev);

        if (ev.isSuccess()) {
            pushToWx("invoice_task_pdf", payload);
            pushToWecom("invoice_task_success", payload);
            notifyApplicantSafely(ev);
        } else if (ev.isFailed()) {
            pushToWecom("invoice_task_failed", payload);
            pushToFinmaster("invoice_task_followup", payload);
        } else if (ev.isRedPending()) {
            pushToFinmaster("invoice_red_pending", payload);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 客户群路由变更 — WC 缓存失效关键路径
    // ═══════════════════════════════════════════════════════════════

    @Async
    @EventListener
    public void onCustomerGroupChanged(CustomerGroupChangedEvent ev) {
        log.info("[FN-05] customer-group changed: id={}, chatId={}, type={}, sales={}, cs={}, accountant={}, gov={}",
                ev.getCustomerGroupId(), ev.getWecomChatId(), ev.getChangeType(),
                ev.getSalesEmployeeId(), ev.getCsEmployeeId(),
                ev.getAccountantEmployeeId(), ev.getGovEmployeeId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerGroupId", ev.getCustomerGroupId());
        payload.put("wecomChatId", ev.getWecomChatId());
        payload.put("customerId", ev.getCustomerId());
        payload.put("changeType", ev.getChangeType() != null ? ev.getChangeType().name() : null);
        payload.put("salesEmployeeId", ev.getSalesEmployeeId());
        payload.put("csEmployeeId", ev.getCsEmployeeId());
        payload.put("accountantEmployeeId", ev.getAccountantEmployeeId());
        payload.put("govEmployeeId", ev.getGovEmployeeId());
        payload.put("cacheInvalidationHint", ev.getWecomChatId() != null
                ? "by_chat_id:" + ev.getWecomChatId()
                : "full_reload");

        pushToWecom("customer_group_changed", payload);
    }

    // ═══════════════════════════════════════════════════════════════
    // 下游推送辅助
    // ═══════════════════════════════════════════════════════════════

    /**
     * FN-T4：SUCCESS 时给申请人发短信+邮件。
     * <p>本方法是 fail-soft：通知失败入 DLQ，不阻塞其他下游推送。
     */
    void notifyApplicantSafely(InvoiceTaskCompletedEvent ev) {
        if (invoiceNotificationService == null) {
            log.debug("[FN-T4] invoiceNotificationService 未注入（测试环境？），跳过 task={}", ev.getTaskId());
            return;
        }
        // payload 用于进 DLQ 后还原上下文重试
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventType", "invoice_task_applicant_notify");
        envelope.put("source", "financial");
        envelope.put("taskId", ev.getTaskId());
        envelope.put("applicantPhone", ev.getApplicantPhone());
        envelope.put("applicantEmail", ev.getApplicantEmail());
        envelope.put("applicantName", ev.getApplicantName());
        envelope.put("invoiceNumber", ev.getInvoiceNumber());
        envelope.put("pdfUrl", ev.getPdfUrl());
        try {
            invoiceNotificationService.notifyApplicant(ev.getTaskId());
            invoiceNotificationService.markNotified(ev.getTaskId());
            log.info("[FN-T4] applicant 通知已发：taskId={}", ev.getTaskId());
        } catch (Exception ex) {
            log.warn("[FN-T4] applicant 通知失败：taskId={}, err={}", ev.getTaskId(), ex.getMessage());
            enqueueDlq("applicant", "invoice_task_applicant_notify", envelope,
                    ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName(), 1);
            // FN9-T1：用户级通知，入队后立即触发一次快重试（异步，绕过 5 分钟冷却期）
            if (notifyDlqWorker != null) {
                try {
                    notifyDlqWorker.triggerFastRetry("applicant");
                } catch (Exception fe) {
                    log.warn("[FN9-T1] applicant 快重试触发失败：taskId={}, err={}", ev.getTaskId(), fe.getMessage());
                }
            }
        }
    }

    void pushToWx(String eventType, Map<String, Object> payload) {
        postOrSkip(wxPushUrl, eventType, payload, "wx");
    }

    void pushToWecom(String eventType, Map<String, Object> payload) {
        postOrSkip(wecomPushUrl, eventType, payload, "wecom");
    }

    void pushToFinmaster(String eventType, Map<String, Object> payload) {
        postOrSkip(finmasterTaskUrl, eventType, payload, "finmaster");
    }

    /**
     * 真发 + 重试 + DLQ。
     */
    private void postOrSkip(String url, String eventType, Map<String, Object> payload, String channel) {
        if (url == null || url.isBlank()) {
            // 配置真空 — 当前测试环境/未配 PREP-016 占位，仅 log
            log.debug("[FN-05] skip {}: app.notify.*-url 未配置, eventType={}, payload={}",
                    channel, eventType, payload);
            return;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventType", eventType);
        envelope.put("source", "financial");
        envelope.put("payload", payload);

        long startNanos = System.nanoTime();
        Exception lastError = null;
        int attempts = 0;

        for (int i = 0; i <= maxRetry; i++) {
            attempts = i + 1;
            try {
                doPost(url, envelope);
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                NotifyMetrics.success(channel, attempts, elapsedMs);
                return;
            } catch (RestClientResponseException ex) {
                int code = ex.getStatusCode().value();
                if (code >= 400 && code < 500) {
                    // 4xx：业务拒绝，立即入 DLQ 不重试
                    log.warn("[FN-05] push to {} rejected (4xx, no retry): url={}, eventType={}, status={}, body={}",
                            channel, url, eventType, code, ex.getResponseBodyAsString());
                    enqueueDlq(channel, eventType, envelope, "HTTP " + code + ": " + ex.getStatusText(), attempts);
                    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                    NotifyMetrics.failure(channel, "http_" + code, attempts, elapsedMs);
                    return;
                }
                // 5xx：可重试
                lastError = ex;
                log.warn("[FN-05] push to {} got 5xx (attempt {}/{}): url={}, eventType={}, status={}",
                        channel, attempts, maxRetry + 1, url, eventType, code);
            } catch (Exception ex) {
                // IOException / Timeout / 网络层错误 → 重试
                lastError = ex;
                log.warn("[FN-05] push to {} failed (attempt {}/{}): url={}, eventType={}, err={}",
                        channel, attempts, maxRetry + 1, url, eventType, ex.getMessage());
                if (!isRetryable(ex)) {
                    // 非可重试异常 → 直接入 DLQ
                    break;
                }
            }
            if (i < maxRetry) {
                sleepBackoff(i);
            }
        }

        // 所有重试都用光
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        String reason = lastError == null ? "unknown" : lastError.getClass().getSimpleName();
        NotifyMetrics.failure(channel, reason, attempts, elapsedMs);
        String errMsg = lastError == null ? "unknown" : lastError.getMessage();
        enqueueDlq(channel, eventType, envelope, errMsg, attempts);
    }

    /**
     * 单次 POST — 用包级可见的方法方便测试 spy。
     */
    void doPost(String url, Map<String, Object> envelope) {
        restClient.post()
                .uri(url)
                .body(envelope)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * 入 DLQ 时不能抛 — 已经在 fail path，DLQ 再挂就只剩 log 兜底。
     */
    private void enqueueDlq(String channel, String eventType, Map<String, Object> envelope,
                            String lastError, int retryCount) {
        if (dlq == null) {
            // 没配 Redis 或测试环境 — 仅 log，不影响业务
            log.error("[FN-05] DLQ unavailable, dropping: channel={}, eventType={}, lastError={}, payload={}",
                    channel, eventType, lastError, JSON.toJSONString(envelope));
            return;
        }
        try {
            dlq.enqueue(channel, eventType, envelope, lastError, retryCount);
        } catch (Exception ex) {
            log.error("[FN-05] DLQ enqueue failed: channel={}, eventType={}, err={}",
                    channel, eventType, ex.getMessage(), ex);
        }
    }

    /**
     * 指数退避：500ms / 1.5s / 3s。
     */
    private static void sleepBackoff(int attemptIndex) {
        long ms = switch (attemptIndex) {
            case 0 -> 500L;
            case 1 -> 1500L;
            case 2 -> 3000L;
            default -> 3000L;
        };
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 仅对 IO / 超时 / 5xx 重试；其他立即入 DLQ。
     */
    static boolean isRetryable(Exception ex) {
        if (ex instanceof IOException) return true;
        if (ex instanceof SocketTimeoutException) return true;
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause instanceof IOException) return true;
            if (cause instanceof SocketTimeoutException) return true;
            cause = cause.getCause();
        }
        // RestClient 在 ResourceAccessException 包装 IO 异常
        String name = ex.getClass().getSimpleName();
        return "ResourceAccessException".equals(name);
    }

    private static Map<String, Object> baseDeclarePayload(DeclareTaskStatusChangedEvent ev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", ev.getTaskId());
        m.put("accountSetsId", ev.getAccountSetsId());
        m.put("customerId", ev.getCustomerId());
        m.put("taxType", ev.getTaxType());
        m.put("periodYear", ev.getPeriodYear());
        m.put("periodMonth", ev.getPeriodMonth());
        m.put("periodQuarter", ev.getPeriodQuarter());
        m.put("fromStatus", ev.getFromStatus() != null ? ev.getFromStatus().name() : null);
        m.put("toStatus", ev.getToStatus() != null ? ev.getToStatus().name() : null);
        m.put("executor", ev.getExecutor());
        m.put("errorMsg", ev.getErrorMsg());
        return m;
    }

    private static Map<String, Object> baseInvoicePayload(InvoiceTaskCompletedEvent ev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", ev.getTaskId());
        m.put("accountSetsId", ev.getAccountSetsId());
        m.put("customerId", ev.getCustomerId());
        m.put("isRedInvoice", ev.isRedInvoice());
        m.put("originalInvoiceId", ev.getOriginalInvoiceId());
        m.put("finalStatus", ev.getFinalStatus());
        m.put("invoiceCode", ev.getInvoiceCode());
        m.put("invoiceNumber", ev.getInvoiceNumber());
        m.put("errorMsg", ev.getErrorMsg());
        m.put("redPendingReason", ev.getRedPendingReason());
        m.put("requiresManualFollowup", InvoiceTask.STATUS_FAILED.equals(ev.getFinalStatus())
                || InvoiceTask.STATUS_RED_PENDING.equals(ev.getFinalStatus()));
        return m;
    }
}
