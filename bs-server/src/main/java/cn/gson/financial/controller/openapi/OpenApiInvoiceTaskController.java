package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.event.NotifyDeadLetterQueue;
import cn.gson.financial.event.NotifyDlqWorker;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.exception.NotificationException;
import cn.gson.financial.kernel.model.entity.InvoiceTask;
import cn.gson.financial.kernel.model.repo.InvoiceTaskRepository;
import cn.gson.financial.kernel.service.InvoiceNotificationService;
import cn.gson.financial.kernel.service.InvoiceTaskService;
import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * 开放 API — 开票任务（FN-04）
 * <p>
 * 给 BT-15 / WX-01 / FM-04 提供统一的开票任务入口。
 * 路径前缀 /openapi/v1/invoice-task，全部接口免鉴权（@SaIgnore）。
 * <p>
 * 状态机：
 *   CUSTOMER_REQUESTED → CS_REVIEWING → APPROVED → EXECUTING → SUCCESS / FAILED / RED_PENDING
 * <p>
 * 业务约束：
 *   - 所有任务都必须 APPROVED → EXECUTING（强制审核）
 *   - 红字发票（is_red_invoice=true）额外要求：必须真的经过 CS_REVIEWING（reviewer_id 非空）才能进 EXECUTING
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/invoice-task")
@SaIgnore
public class OpenApiInvoiceTaskController {

    private final InvoiceTaskService invoiceTaskService;
    private final InvoiceNotificationService invoiceNotificationService;
    /** FN-INV-T3（132）按订单号查任务（收银端进度展示用） */
    private final InvoiceTaskRepository invoiceTaskRepository;

    /** FN7-T1：REJECT 通知失败时入队重试；测试时可 mock，required=false 兼容旧测试 */
    @Autowired(required = false)
    private NotifyDeadLetterQueue notifyDlq;

    /** FN9-T1：用户级通知入队后立即触发一次快重试（绕过 5 分钟冷却期）；required=false 兼容旧测试 */
    @Autowired(required = false)
    private NotifyDlqWorker notifyDlqWorker;

    /** REJECT 通知失败入 DLQ 时用的 channel 名（与 wx/wecom/finmaster/applicant 并列） */
    static final String DLQ_CHANNEL_APPLICANT_REJECT = "applicant-reject";
    static final String DLQ_EVENT_APPLICANT_REJECT = "applicant_reject_notify";
    /** FN8-T2 RED_LETTER 通知失败入 DLQ 时用的 channel 名 */
    static final String DLQ_CHANNEL_APPLICANT_RED_LETTER = "applicant-red-letter";
    static final String DLQ_EVENT_APPLICANT_RED_LETTER = "applicant_red_letter_notify";

    // ═══════════════════════════════════════════════════════════════
    // 1. 创建任务（默认 status=CUSTOMER_REQUESTED）
    //    POST /openapi/v1/invoice-task
    // ═══════════════════════════════════════════════════════════════

    /** @deprecated FN-U-T5（140）：开票任务统一在 collect 表，invoice_task 已退役只读；本写端点不再被调用。 */
    @Deprecated
    @PostMapping
    public JsonResult create(@RequestBody InvoiceTaskReq req) {
        String err = validate(req);
        if (err != null) {
            return JsonResult.failure(err);
        }
        try {
            InvoiceTask saved = invoiceTaskService.create(toEntity(req, null));
            return JsonResult.successful(toDto(saved));
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("openapi invoice-task 创建失败", e);
            return JsonResult.failure(e.getMessage() != null ? e.getMessage() : "创建失败");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. 列表（分页）
    //    GET /openapi/v1/invoice-task?status=&customerId=&accountSetsId=&isRedInvoice=&page=&size=
    // ═══════════════════════════════════════════════════════════════

    /** @deprecated FN-U-T5（140）：BT 改读 collect /tasks 领任务，本 list 不再用于扫码直开。 */
    @Deprecated
    @GetMapping
    public JsonResult list(@RequestParam(required = false) String status,
                           @RequestParam(required = false) Long customerId,
                           @RequestParam(required = false) Long accountSetsId,
                           @RequestParam(required = false) Boolean isRedInvoice,
                           @RequestParam(required = false) String source,
                           @RequestParam(required = false, defaultValue = "1") Integer page,
                           @RequestParam(required = false, defaultValue = "20") Integer size) {
        if (page == null || page < 1) page = 1;
        if (size == null || size < 1) size = 20;
        if (size > 500) size = 500;

        // FN2-T3（135）source 过滤：BT 扫码直开 poller 必须传 source=scan-direct
        Page<InvoiceTask> p = invoiceTaskService.page(status, customerId, accountSetsId,
                isRedInvoice, source, page, size);

        List<Map<String, Object>> rows = new ArrayList<>(p.getContent().size());
        for (InvoiceTask t : p.getContent()) {
            rows.add(toDto(t));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", rows);
        // 用 (int) 包装绕过全局 Long→String 序列化器
        data.put("total", (int) p.getTotalElements());
        data.put("page", page);
        data.put("size", size);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. 详情
    //    GET /openapi/v1/invoice-task/{id}
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/{id}")
    public JsonResult detail(@PathVariable Long id) {
        return invoiceTaskService.findById(id)
                .map(t -> JsonResult.successful(toDto(t)))
                .orElseGet(() -> JsonResult.failure("invoice_task 不存在：" + id));
    }

    // ═══════════════════════════════════════════════════════════════
    // 3b. FN-INV-T3（132）按订单号查任务（收银端按 orderNo 拉开票进度）
    //     GET /openapi/v1/invoice-task/by-order/{orderNo}
    //     返：status（APPROVED=开票中 / EXECUTING=开票中 / SUCCESS=已开票 / FAILED=失败 …）
    //         + invoiceCode/invoiceNumber/pdfUrl（SUCCESS 时）+ errorMsg（FAILED 时）
    // ═══════════════════════════════════════════════════════════════

    /** @deprecated FN-U-T5（140）：收银端进度改查 collect GET /invoice-collect/{orderNo}（含发票号+文件）。 */
    @Deprecated
    @GetMapping("/by-order/{orderNo}")
    public JsonResult getByOrderNo(@PathVariable String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return JsonResult.failure("orderNo 必填").setData(java.util.Map.of("errorType", "VALIDATION"));
        }
        return invoiceTaskRepository.findByOrderNo(orderNo.trim())
                .map(t -> JsonResult.successful(toDto(t)))
                .orElseGet(() -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("errorType", "NOT_FOUND");
                    data.put("orderNo", orderNo);
                    return JsonResult.failure("无该订单的开票任务：" + orderNo).setData(data);
                });
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. CUSTOMER_REQUESTED → CS_REVIEWING
    //    PUT /openapi/v1/invoice-task/{id}/start-review
    // ═══════════════════════════════════════════════════════════════

    @PutMapping("/{id}/start-review")
    public JsonResult startReview(@PathVariable Long id) {
        try {
            InvoiceTask t = invoiceTaskService.startReview(id);
            return JsonResult.successful(toDto(t));
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (IllegalStateException e) {
            return failureWithType(e.getMessage(), "ILLEGAL_TRANSITION");
        } catch (Exception e) {
            log.error("openapi invoice-task start-review 失败", e);
            return JsonResult.failure(e.getMessage() != null ? e.getMessage() : "失败");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. CS_REVIEWING → APPROVED 或 → FAILED
    //    PUT /openapi/v1/invoice-task/{id}/review
    //    body: { reviewerId, approved: true/false, comment }
    // ═══════════════════════════════════════════════════════════════

    @PutMapping("/{id}/review")
    public JsonResult review(@PathVariable Long id, @RequestBody ReviewReq req) {
        if (req == null) {
            return JsonResult.failure("body 不能为空");
        }
        if (req.getReviewerId() == null) {
            return JsonResult.failure("reviewerId 不能为空");
        }
        if (req.getApproved() == null) {
            return JsonResult.failure("approved 不能为空");
        }
        try {
            InvoiceTask t = invoiceTaskService.review(id, req.getReviewerId(),
                    Boolean.TRUE.equals(req.getApproved()), req.getComment());
            return JsonResult.successful(toDto(t));
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (IllegalStateException e) {
            return failureWithType(e.getMessage(), "ILLEGAL_TRANSITION");
        } catch (Exception e) {
            log.error("openapi invoice-task review 失败", e);
            return JsonResult.failure(e.getMessage() != null ? e.getMessage() : "失败");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 6. APPROVED → EXECUTING（红字额外校验：必须有 reviewer_id）
    //    PUT /openapi/v1/invoice-task/{id}/start
    //    可选 body: { executor }
    // ═══════════════════════════════════════════════════════════════

    @PutMapping("/{id}/start")
    public JsonResult start(@PathVariable Long id, @RequestBody(required = false) StartReq req) {
        String executor = req != null ? req.getExecutor() : null;
        try {
            InvoiceTask t = invoiceTaskService.start(id, executor);
            return JsonResult.successful(toDto(t));
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (IllegalStateException e) {
            // 红字约束失败也归 ILLEGAL_TRANSITION，但保留原始 message
            String type = e.getMessage() != null && e.getMessage().contains("红字")
                    ? "RED_INVOICE_REVIEW_REQUIRED" : "ILLEGAL_TRANSITION";
            return failureWithType(e.getMessage(), type);
        } catch (Exception e) {
            log.error("openapi invoice-task start 失败", e);
            return JsonResult.failure(e.getMessage() != null ? e.getMessage() : "失败");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 7. EXECUTING → SUCCESS/FAILED/RED_PENDING
    //    POST /openapi/v1/invoice-task/{id}/result
    //    body: { status, invoiceCode, invoiceNumber, errorMsg, redPendingReason }
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/{id}/result")
    public JsonResult result(@PathVariable Long id, @RequestBody ResultReq req) {
        if (req == null) {
            return JsonResult.failure("body 不能为空");
        }
        if (StrUtil.isBlank(req.getStatus())) {
            return JsonResult.failure("status 不能为空");
        }
        try {
            InvoiceTask t = invoiceTaskService.complete(id,
                    req.getStatus(),
                    req.getInvoiceCode(),
                    req.getInvoiceNumber(),
                    req.getErrorMsg(),
                    req.getRedPendingReason(),
                    req.getPdfUrl());        // FN-FIX-01
            return JsonResult.successful(toDto(t));
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (IllegalStateException e) {
            return failureWithType(e.getMessage(), "ILLEGAL_TRANSITION");
        } catch (Exception e) {
            log.error("openapi invoice-task result 失败", e);
            return JsonResult.failure(e.getMessage() != null ? e.getMessage() : "失败");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 8. FN-T5 / FN6-T1 申请人通知触发（invoice-print SUCCESS 或 REJECT 后调）
    //    POST /openapi/v1/invoice-task/{id}/notify?type=CONFIRM|REJECT&comment=xxx
    //    body 可选：{ "force": true } —— CONFIRM 时已通知过且 force=true 强制重发；REJECT 忽略 force
    //    type=CONFIRM 默认值，走原 SUCCESS 通知 + notified_at 幂等
    //    type=REJECT 走驳回通知，不走幂等（每次驳回都发一次）
    // ═══════════════════════════════════════════════════════════════

    static final String NOTIFY_TYPE_CONFIRM = "CONFIRM";
    static final String NOTIFY_TYPE_REJECT = "REJECT";
    static final String NOTIFY_TYPE_RED_LETTER = "RED_LETTER";

    @PostMapping("/{id}/notify")
    public JsonResult notify(@PathVariable Long id,
                             @RequestParam(required = false, defaultValue = NOTIFY_TYPE_CONFIRM) String type,
                             @RequestParam(required = false) String comment,
                             @RequestBody(required = false) NotifyReq req) {
        if (!NOTIFY_TYPE_CONFIRM.equals(type)
                && !NOTIFY_TYPE_REJECT.equals(type)
                && !NOTIFY_TYPE_RED_LETTER.equals(type)) {
            return JsonResult.failure("type 必须是 CONFIRM / REJECT / RED_LETTER，当前: " + type).setData(
                    Map.of("errorType", "ILLEGAL_TYPE"));
        }
        Optional<InvoiceTask> opt = invoiceTaskService.findById(id);
        if (opt.isEmpty()) {
            return JsonResult.failure("invoice_task 不存在：" + id);
        }
        InvoiceTask t = opt.get();

        if (NOTIFY_TYPE_RED_LETTER.equals(type)) {
            // FN8-T2：红冲通知，不走幂等；失败入 DLQ channel='applicant-red-letter'
            try {
                invoiceNotificationService.notifyRedLetterIssued(id);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("taskId", id);
                data.put("type", NOTIFY_TYPE_RED_LETTER);
                data.put("skipped", false);
                data.put("enqueued", false);
                return JsonResult.successful(data);
            } catch (IllegalArgumentException e) {
                return JsonResult.failure(e.getMessage());
            } catch (NotificationException e) {
                log.warn("openapi invoice-task notify RED_LETTER 失败 → 入 DLQ：id={}, err={}", id, e.getMessage());
                boolean enqueued = enqueueRedLetterDlq(id, e.getMessage());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("taskId", id);
                data.put("type", NOTIFY_TYPE_RED_LETTER);
                data.put("skipped", false);
                data.put("enqueued", enqueued);
                if (!enqueued) {
                    data.put("errorType", "NOTIFY_FAILED_DLQ_UNAVAILABLE");
                    return JsonResult.successful(data).setMsg("通知失败且 DLQ 不可用");
                }
                return JsonResult.successful(data).setMsg("已入队待重试");
            } catch (Exception e) {
                log.error("openapi invoice-task notify RED_LETTER 异常：id={}", id, e);
                boolean enqueued = enqueueRedLetterDlq(id,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("taskId", id);
                data.put("type", NOTIFY_TYPE_RED_LETTER);
                data.put("enqueued", enqueued);
                return JsonResult.successful(data).setMsg(enqueued ? "已入队待重试" : "红冲通知失败");
            }
        }

        if (NOTIFY_TYPE_REJECT.equals(type)) {
            // FN6-T1：驳回通知不走幂等，不要求 SUCCESS 状态（REJECT 状态由 invoice-print 端管理）
            // FN7-T1：通知失败时入 DLQ，端点仍返 200 + msg=已入队待重试，让 IP6-T2 fire-and-forget
            try {
                invoiceNotificationService.notifyRejected(id, comment);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("taskId", id);
                data.put("type", NOTIFY_TYPE_REJECT);
                data.put("comment", comment);
                data.put("skipped", false);
                data.put("enqueued", false);
                return JsonResult.successful(data);
            } catch (IllegalArgumentException e) {
                // task 不存在等业务参数错 → 不入 DLQ
                return JsonResult.failure(e.getMessage());
            } catch (NotificationException e) {
                log.warn("openapi invoice-task notify REJECT 失败 → 入 DLQ：id={}, err={}", id, e.getMessage());
                boolean enqueued = enqueueRejectDlq(id, comment, e.getMessage());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("taskId", id);
                data.put("type", NOTIFY_TYPE_REJECT);
                data.put("comment", comment);
                data.put("skipped", false);
                data.put("enqueued", enqueued);
                if (!enqueued) {
                    data.put("errorType", "NOTIFY_FAILED_DLQ_UNAVAILABLE");
                    // DLQ 自己挂时仍返 200 但带 errorType，调用方可选择重试
                    return JsonResult.successful(data).setMsg("通知失败且 DLQ 不可用");
                }
                return JsonResult.successful(data).setMsg("已入队待重试");
            } catch (Exception e) {
                log.error("openapi invoice-task notify REJECT 异常：id={}", id, e);
                boolean enqueued = enqueueRejectDlq(id, comment,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("taskId", id);
                data.put("type", NOTIFY_TYPE_REJECT);
                data.put("enqueued", enqueued);
                return JsonResult.successful(data).setMsg(enqueued ? "已入队待重试" : "驳回通知失败");
            }
        }

        // CONFIRM 路径（原 FN-T5 流程，不变）
        boolean force = req != null && Boolean.TRUE.equals(req.getForce());
        if (!InvoiceTask.STATUS_SUCCESS.equals(t.getStatus())) {
            return JsonResult.failure("只有 SUCCESS 任务能触发通知，当前 status=" + t.getStatus()).setData(
                    Map.of("errorType", "ILLEGAL_STATUS"));
        }
        if (t.getNotifiedAt() != null && !force) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", id);
            data.put("notifiedAt", t.getNotifiedAt());
            data.put("skipped", true);
            return JsonResult.successful(data).setMsg("已通知过");
        }
        try {
            invoiceNotificationService.notifyApplicant(id);
            invoiceNotificationService.markNotified(id);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", id);
            data.put("notifiedAt", invoiceTaskService.findById(id)
                    .map(InvoiceTask::getNotifiedAt).orElse(null));
            data.put("skipped", false);
            data.put("forced", force);
            return JsonResult.successful(data);
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (NotificationException e) {
            log.warn("openapi invoice-task notify 失败：id={}, err={}", id, e.getMessage());
            return JsonResult.failure(e.getMessage()).setData(
                    Map.of("errorType", "NOTIFY_FAILED"));
        } catch (Exception e) {
            log.error("openapi invoice-task notify 异常：id={}", id, e);
            return JsonResult.failure(e.getMessage() != null ? e.getMessage() : "通知失败");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 9. 批量创建（独立事务，逐条提交，部分失败不影响其他）
    //    POST /openapi/v1/invoice-task/batch
    // ═══════════════════════════════════════════════════════════════

    /** @deprecated FN-U-T5（140）：开票任务统一在 collect 表，invoice_task 已退役只读；本批量写端点不再被调用。 */
    @Deprecated
    @PostMapping("/batch")
    public JsonResult batch(@RequestBody BatchReq req) {
        if (req == null || req.getTasks() == null || req.getTasks().isEmpty()) {
            return JsonResult.failure("tasks 不能为空");
        }
        List<InvoiceTaskReq> items = req.getTasks();
        List<Map<String, Object>> results = new ArrayList<>(items.size());
        int succeeded = 0;
        int failed = 0;
        for (int i = 0; i < items.size(); i++) {
            InvoiceTaskReq item = items.get(i);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("index", i);
            try {
                String err = validate(item);
                if (err != null) {
                    throw new IllegalArgumentException(err);
                }
                InvoiceTask saved = invoiceTaskService.createInNewTransaction(toEntity(item, null));
                r.put("status", "ok");
                r.put("id", saved.getId());
                succeeded++;
            } catch (Exception e) {
                r.put("status", "error");
                r.put("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                failed++;
            }
            results.add(r);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalSubmitted", items.size());
        data.put("succeeded", succeeded);
        data.put("failed", failed);
        data.put("results", results);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════════

    private static String validate(InvoiceTaskReq req) {
        if (req == null) return "请求体不能为空";
        if (req.getAccountSetsId() == null) return "accountSetsId 不能为空";
        if (req.getCustomerId() == null) return "customerId 不能为空";
        if (Boolean.TRUE.equals(req.getIsRedInvoice()) && req.getOriginalInvoiceId() == null) {
            return "红字发票必须提供 originalInvoiceId";
        }
        return null;
    }

    private static InvoiceTask toEntity(InvoiceTaskReq req, InvoiceTask base) {
        InvoiceTask t = base != null ? base : new InvoiceTask();
        t.setAccountSetsId(req.getAccountSetsId());
        t.setCustomerId(req.getCustomerId());
        if (req.getInvoiceType() != null && !req.getInvoiceType().isBlank()) {
            t.setInvoiceType(req.getInvoiceType().trim());
        }
        if (req.getIsRedInvoice() != null) {
            t.setIsRedInvoice(req.getIsRedInvoice());
        }
        t.setOriginalInvoiceId(req.getOriginalInvoiceId());
        t.setRequestAmount(req.getRequestAmount());
        t.setRequestTaxAmount(req.getRequestTaxAmount());
        t.setItemsJson(req.getItemsJson());
        t.setPurchaserName(req.getPurchaserName());
        t.setPurchaserCreditCode(req.getPurchaserCreditCode());
        t.setRequestRemark(req.getRequestRemark());
        // FN-T1：申请人联系方式（开票完成后短信+邮件通知用）
        if (req.getApplicantPhone() != null) {
            t.setApplicantPhone(req.getApplicantPhone().trim());
        }
        if (req.getApplicantEmail() != null) {
            t.setApplicantEmail(req.getApplicantEmail().trim());
        }
        if (req.getApplicantName() != null) {
            t.setApplicantName(req.getApplicantName().trim());
        }
        // FN4-T2：开票项目 / 税收编码 / 税率（与 invoice-print 字段对齐，结构化代替 requestRemark 拼字符串）
        if (req.getItemName() != null) {
            t.setItemName(req.getItemName().trim());
        }
        if (req.getItemCode() != null) {
            t.setItemCode(req.getItemCode().trim());
        }
        if (req.getTaxRate() != null) {
            t.setTaxRate(req.getTaxRate());
        }
        // FN8-T1：红冲发票 4 字段透传
        if (req.getIsRedInvoiceRequested() != null) {
            t.setIsRedInvoiceRequested(req.getIsRedInvoiceRequested());
        }
        if (req.getRedLetterOfTaskId() != null) {
            t.setRedLetterOfTaskId(req.getRedLetterOfTaskId());
        }
        if (req.getRedInvoiceNumber() != null) {
            t.setRedInvoiceNumber(req.getRedInvoiceNumber().trim());
        }
        if (req.getRedPdfUrl() != null) {
            t.setRedPdfUrl(req.getRedPdfUrl().trim());
        }
        return t;
    }

    private static Map<String, Object> toDto(InvoiceTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("orderNo", t.getOrderNo());                 // FN-INV-T1（132）
        m.put("regionCode", t.getRegionCode());           // FN2-T2（135）账套省份
        m.put("source", t.getSource());                    // FN2-T3（135）任务来源
        m.put("accountSetsId", t.getAccountSetsId());
        m.put("customerId", t.getCustomerId());
        m.put("invoiceType", t.getInvoiceType());
        m.put("isRedInvoice", t.getIsRedInvoice());
        m.put("originalInvoiceId", t.getOriginalInvoiceId());
        m.put("status", t.getStatus());
        m.put("requestAmount", t.getRequestAmount());
        m.put("requestTaxAmount", t.getRequestTaxAmount());
        m.put("itemsJson", t.getItemsJson());
        m.put("purchaserName", t.getPurchaserName());
        m.put("purchaserCreditCode", t.getPurchaserCreditCode());
        m.put("requestRemark", t.getRequestRemark());
        m.put("reviewerId", t.getReviewerId());
        m.put("reviewedAt", t.getReviewedAt());
        m.put("reviewComment", t.getReviewComment());
        m.put("executor", t.getExecutor());
        m.put("invoiceCode", t.getInvoiceCode());
        m.put("invoiceNumber", t.getInvoiceNumber());
        m.put("errorMsg", t.getErrorMsg());
        m.put("redPendingReason", t.getRedPendingReason());
        m.put("pdfUrl", t.getPdfUrl());     // FN-FIX-01
        // FN-T1：申请人联系方式透传给调用方
        m.put("applicantPhone", t.getApplicantPhone());
        m.put("applicantEmail", t.getApplicantEmail());
        m.put("applicantName", t.getApplicantName());
        // FN-T5：通知时刻（已通知过则非空，幂等判断用）
        m.put("notifiedAt", t.getNotifiedAt());
        // FN4-T2：开票项目 / 税收编码 / 税率
        m.put("itemName", t.getItemName());
        m.put("itemCode", t.getItemCode());
        m.put("taxRate", t.getTaxRate());
        // FN8-T1：红冲发票 4 字段
        m.put("isRedInvoiceRequested", t.getIsRedInvoiceRequested());
        m.put("redLetterOfTaskId", t.getRedLetterOfTaskId());
        m.put("redInvoiceNumber", t.getRedInvoiceNumber());
        m.put("redPdfUrl", t.getRedPdfUrl());
        m.put("version", t.getVersion());
        m.put("createdAt", t.getCreatedAt());
        m.put("updatedAt", t.getUpdatedAt());
        m.put("submittedAt", t.getSubmittedAt());
        return m;
    }

    private static JsonResult failureWithType(String msg, String errorType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorType", errorType);
        return JsonResult.failure(msg).setData(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 请求 DTO
    // ═══════════════════════════════════════════════════════════════

    @Data
    public static class InvoiceTaskReq {
        private Long accountSetsId;
        private Long customerId;
        private String invoiceType;
        private Boolean isRedInvoice;
        private Long originalInvoiceId;
        private BigDecimal requestAmount;
        private BigDecimal requestTaxAmount;
        private String itemsJson;
        private String purchaserName;
        private String purchaserCreditCode;
        private String requestRemark;
        /** FN-T1：申请人手机号（短信通知用，可空，对老调用方默认 null 兼容） */
        private String applicantPhone;
        /** FN-T1：申请人邮箱（邮件通知用，可空） */
        private String applicantEmail;
        /** FN-T1：申请人姓名（短信/邮件抬头用，可空） */
        private String applicantName;
        /** FN4-T2：开票项目名（与 invoice-print 字段对齐） */
        private String itemName;
        /** FN4-T2：商品和服务税收分类编码 19 位 */
        private String itemCode;
        /** FN4-T2：税率（小数，如 0.0600 = 6%） */
        private BigDecimal taxRate;
        /** FN8-T1：是否已发起红冲申请 */
        private Boolean isRedInvoiceRequested;
        /** FN8-T1：红冲源任务 id（红字发票任务指向被红冲的蓝字任务） */
        private Long redLetterOfTaskId;
        /** FN8-T1：红字发票号 */
        private String redInvoiceNumber;
        /** FN8-T1：红字发票 PDF URL */
        private String redPdfUrl;
    }

    @Data
    public static class ReviewReq {
        private Long reviewerId;
        private Boolean approved;
        private String comment;
    }

    @Data
    public static class StartReq {
        private String executor;
    }

    @Data
    public static class ResultReq {
        private String status;          // SUCCESS / FAILED / RED_PENDING
        private String invoiceCode;
        private String invoiceNumber;
        private String errorMsg;
        private String redPendingReason;
        /** FN-FIX-01：BT 开成功后回写 PDF URL；SUCCESS 时使用，其他状态可不传 */
        private String pdfUrl;
    }

    @Data
    public static class BatchReq {
        private List<InvoiceTaskReq> tasks;
    }

    /** FN-T5 /notify body：force=true 时已通知过的任务也重发 */
    @Data
    public static class NotifyReq {
        private Boolean force;
    }

    /**
     * FN7-T1：把 REJECT 失败的通知请求塞进 DLQ，由 NotifyDlqWorker 后台扫描重试。
     * <p>
     * DLQ 自己挂时（dlq=null 或 enqueue 抛异常），只 log 兜底；不抛回端点。
     * 返回是否入队成功，端点据此决定 msg 文案。
     */
    private boolean enqueueRejectDlq(Long taskId, String comment, String lastError) {
        if (notifyDlq == null) {
            log.error("[FN7-T1] NotifyDeadLetterQueue 未注入，REJECT 通知失败无法入队兜底: taskId={}, err={}",
                    taskId, lastError);
            return false;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("taskId", taskId);
        envelope.put("comment", comment);
        try {
            notifyDlq.enqueue(DLQ_CHANNEL_APPLICANT_REJECT, DLQ_EVENT_APPLICANT_REJECT,
                    envelope, lastError, 1);
            // FN9-T1：用户级通知，入队后立即触发一次快重试（异步，绕过 5 分钟冷却期）
            triggerFastRetry(DLQ_CHANNEL_APPLICANT_REJECT);
            return true;
        } catch (Exception ex) {
            log.error("[FN7-T1] DLQ enqueue 自己挂了: taskId={}, err={}", taskId, ex.getMessage(), ex);
            return false;
        }
    }

    /** FN8-T2 RED_LETTER 失败入 DLQ；与 enqueueRejectDlq 同结构，envelope 只含 taskId（红冲信息从 entity 加载） */
    private boolean enqueueRedLetterDlq(Long taskId, String lastError) {
        if (notifyDlq == null) {
            log.error("[FN8-T2] NotifyDeadLetterQueue 未注入，RED_LETTER 通知失败无法入队兜底: taskId={}, err={}",
                    taskId, lastError);
            return false;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("taskId", taskId);
        try {
            notifyDlq.enqueue(DLQ_CHANNEL_APPLICANT_RED_LETTER, DLQ_EVENT_APPLICANT_RED_LETTER,
                    envelope, lastError, 1);
            // FN9-T1：用户级通知，入队后立即触发一次快重试（异步，绕过 5 分钟冷却期）
            triggerFastRetry(DLQ_CHANNEL_APPLICANT_RED_LETTER);
            return true;
        } catch (Exception ex) {
            log.error("[FN8-T2] DLQ enqueue 自己挂了: taskId={}, err={}", taskId, ex.getMessage(), ex);
            return false;
        }
    }

    /**
     * FN9-T1：入队成功后触发一次快重试（绕过冷却期）。worker 未注入（旧测试）时静默跳过。
     * triggerFastRetry 自身 @Async + 内部 try/catch，不会反噬端点。
     */
    private void triggerFastRetry(String channel) {
        if (notifyDlqWorker == null) {
            log.debug("[FN9-T1] NotifyDlqWorker 未注入，跳过快重试: channel={}", channel);
            return;
        }
        try {
            notifyDlqWorker.triggerFastRetry(channel);
        } catch (Exception ex) {
            log.warn("[FN9-T1] 触发快重试失败: channel={}, err={}", channel, ex.getMessage());
        }
    }
}
