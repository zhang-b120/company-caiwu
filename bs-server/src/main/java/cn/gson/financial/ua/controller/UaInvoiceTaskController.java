package cn.gson.financial.ua.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 小程序端 — 开票任务（FN-AL-01 / 对接 wx 端 financial-mock createInvoiceTask 系列）
 * <p>
 * 路径前缀：/ua/invoiceTask（小程序专属域，区别于 BT/XW 桌面端的 /openapi/v1/invoice-task）
 * <p>
 * 7 大写状态机（与 wx/utils/financial-mock.js 对齐）：
 * <pre>
 *   CUSTOMER_REQUESTED → CS_REVIEWING → APPROVED → EXECUTING → SUCCESS
 *                                                            ↘ FAILED(ENGINE_ERROR)
 *                                     ↘ FAILED(CS_REJECTED)
 *   RED_PENDING        ：红冲场景，独立流程
 * </pre>
 * <p>
 * 当前实现：服务端内存状态机，与 wx 端 mock 等价（解锁 wx 切 USE_MOCK=false 联调）。
 * TODO 后续接 InvoiceTask 实体表、客服审核流、开票 RPA 引擎。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/ua/invoiceTask")
@SaIgnore
public class UaInvoiceTaskController {

    /** 任务存储（内存，TODO 改 invoice_task 表） */
    private static final Map<String, TaskState> STORE = new ConcurrentHashMap<>();
    /** 外部申请号 → 任务号 幂等映射（TODO 改 unique 索引） */
    private static final Map<String, String> EXTERNAL_INDEX = new ConcurrentHashMap<>();

    /**
     * 创建开票任务
     * <p>POST /ua/invoiceTask/uaCreateInvoiceTask
     */
    @PostMapping("/uaCreateInvoiceTask")
    public JsonResult uaCreateInvoiceTask(@RequestBody InvoiceTaskReq req) {
        if (req.getAccountSetsId() == null) return JsonResult.failure("accountSetsId 不能为空");
        if (req.getCustomerId() == null) return JsonResult.failure("customerId 不能为空");
        if (isBlank(req.getExternalApplyId())) return JsonResult.failure("externalApplyId 不能为空");
        if (isBlank(req.getPurchaserName())) return JsonResult.failure("purchaserName 不能为空");
        if (isBlank(req.getPurchaserCreditCode())) return JsonResult.failure("purchaserCreditCode 不能为空");
        if (isBlank(req.getInvoiceType())) return JsonResult.failure("invoiceType 不能为空");
        if (isBlank(req.getItemsJson())) return JsonResult.failure("itemsJson 不能为空");

        // 幂等：同 externalApplyId 重复提交返回原 taskId
        String existing = EXTERNAL_INDEX.get(req.getExternalApplyId());
        if (existing != null) {
            log.info("[invoice-task] idempotent return: externalApplyId={}, taskId={}", req.getExternalApplyId(), existing);
            TaskState s = STORE.get(existing);
            return ok(taskId(existing), s != null ? s.currentStatus() : "CUSTOMER_REQUESTED", "已提交开票队列");
        }

        String taskId = "inv_" + System.currentTimeMillis() + "_" + randomSuffix();
        TaskState state = new TaskState(taskId, req, pickFlow());
        STORE.put(taskId, state);
        EXTERNAL_INDEX.put(req.getExternalApplyId(), taskId);
        log.info("[invoice-task] created: taskId={}, externalApplyId={}", taskId, req.getExternalApplyId());
        return ok(taskId, "CUSTOMER_REQUESTED", "已提交开票队列");
    }

    /**
     * 查询任务状态（轮询，silent）
     * <p>GET /ua/invoiceTask/uaGetInvoiceTaskStatus/{taskId}
     */
    @GetMapping("/uaGetInvoiceTaskStatus/{taskId}")
    public JsonResult uaGetInvoiceTaskStatus(@PathVariable String taskId) {
        TaskState state = STORE.get(taskId);
        if (state == null) {
            Map<String, Object> notFound = new LinkedHashMap<>();
            notFound.put("taskId", taskId);
            notFound.put("status", "NOT_FOUND");
            notFound.put("message", "任务不存在");
            return JsonResult.successful(notFound);
        }
        FlowStep cur = state.currentStep();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("status", cur.status);
        data.put("label", cur.label);
        if (cur.pdfUrl != null) data.put("pdfUrl", cur.pdfUrl);
        if (cur.errorMsg != null) data.put("errorMsg", cur.errorMsg);
        if (cur.failureReason != null) data.put("failureReason", cur.failureReason);
        data.put("updatedAt", Instant.now().toString());
        return JsonResult.successful(data);
    }

    /**
     * 失败重试：复用原 taskId，重新进入流程
     * <p>POST /ua/invoiceTask/uaRetryInvoiceTask/{taskId}
     */
    @PostMapping("/uaRetryInvoiceTask/{taskId}")
    public JsonResult uaRetryInvoiceTask(@PathVariable String taskId) {
        TaskState state = STORE.get(taskId);
        if (state == null) {
            // 找不到原任务也接受（避免客户端重复创建）
            state = new TaskState(taskId, null, INVOICE_FLOW_SUCCESS);
            STORE.put(taskId, state);
        } else {
            // 重试统一进入成功流程（mock 行为；TODO 真实业务保留原 payload，重走客服审核）
            state.flow = INVOICE_FLOW_SUCCESS;
            state.startTime = System.currentTimeMillis();
            state.retryCount += 1;
        }
        log.info("[invoice-task] retry: taskId={}, retryCount={}", taskId, state.retryCount);
        return ok(taskId, "CUSTOMER_REQUESTED", "已重新提交");
    }

    // ───────────────────────── 状态机定义 ─────────────────────────

    /** 与 financial-mock.js 完全等价的三条流水线 */
    static class FlowStep {
        final String status;
        final long delayMs;
        final String label;
        final String pdfUrl;
        final String errorMsg;
        final String failureReason;

        FlowStep(String status, long delayMs, String label) {
            this(status, delayMs, label, null, null, null);
        }
        FlowStep(String status, long delayMs, String label, String pdfUrl, String errorMsg, String failureReason) {
            this.status = status;
            this.delayMs = delayMs;
            this.label = label;
            this.pdfUrl = pdfUrl;
            this.errorMsg = errorMsg;
            this.failureReason = failureReason;
        }
    }

    private static final List<FlowStep> INVOICE_FLOW_SUCCESS = List.of(
            new FlowStep("CUSTOMER_REQUESTED", 1500, "已提交"),
            new FlowStep("CS_REVIEWING",       2000, "客服审核中"),
            new FlowStep("APPROVED",           1000, "已通过审核"),
            new FlowStep("EXECUTING",          3000, "开票中"),
            new FlowStep("SUCCESS",               0, "开票完成",
                    "https://mock.qingyicloud.com/invoice-mock.pdf", null, null)
    );

    private static final List<FlowStep> INVOICE_FLOW_CS_REJECTED = List.of(
            new FlowStep("CUSTOMER_REQUESTED", 1500, "已提交"),
            new FlowStep("CS_REVIEWING",       3000, "客服审核中"),
            new FlowStep("FAILED", 0, "客服审核未通过",
                    null, "购买方税号格式不正确，请修改后重新提交", "CS_REJECTED")
    );

    private static final List<FlowStep> INVOICE_FLOW_ENGINE_ERROR = List.of(
            new FlowStep("CUSTOMER_REQUESTED", 1500, "已提交"),
            new FlowStep("CS_REVIEWING",       2000, "客服审核中"),
            new FlowStep("APPROVED",           1000, "已通过审核"),
            new FlowStep("EXECUTING",          2500, "开票中"),
            new FlowStep("FAILED", 0, "开票失败",
                    null, "开票引擎临时故障，可重试", "ENGINE_ERROR")
    );

    /** 60% 成功 / 25% 客服拒绝 / 15% 开票引擎失败（mock 等价行为） */
    private static List<FlowStep> pickFlow() {
        double r = ThreadLocalRandom.current().nextDouble();
        if (r < 0.60) return INVOICE_FLOW_SUCCESS;
        if (r < 0.85) return INVOICE_FLOW_CS_REJECTED;
        return INVOICE_FLOW_ENGINE_ERROR;
    }

    /** 单个任务运行时状态 */
    static class TaskState {
        final String taskId;
        final InvoiceTaskReq payload;
        List<FlowStep> flow;
        long startTime;
        int retryCount;

        TaskState(String taskId, InvoiceTaskReq payload, List<FlowStep> flow) {
            this.taskId = taskId;
            this.payload = payload;
            this.flow = flow;
            this.startTime = System.currentTimeMillis();
            this.retryCount = 0;
        }

        FlowStep currentStep() {
            long elapsed = System.currentTimeMillis() - startTime;
            long cumulative = 0;
            FlowStep cur = flow.get(0);
            for (FlowStep step : flow) {
                cumulative += step.delayMs;
                if (elapsed <= cumulative || step.delayMs == 0) {
                    return step;
                }
                cur = step;
            }
            return cur;
        }

        String currentStatus() {
            return currentStep().status;
        }
    }

    // ───────────────────────── 辅助 ─────────────────────────

    private static JsonResult ok(String taskId, String status, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("status", status);
        data.put("message", message);
        return JsonResult.successful(data);
    }

    private static String taskId(String s) { return s; }
    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private static String randomSuffix() {
        return Long.toString(Math.abs(ThreadLocalRandom.current().nextLong()), 36).substring(0, 6);
    }

    // ───────────────────────── 请求 DTO ─────────────────────────

    /**
     * 创建开票任务请求（字段对齐 wx/utils/financial-mock.js 第 49-58 行）
     * <p>BL-DAY3-01 已补齐：purchaserBankAccount / purchaserPhone / purchaserAddress
     */
    @Data
    public static class InvoiceTaskReq {
        private Long accountSetsId;
        private Long customerId;
        private String externalApplyId;        // 幂等键
        // 购方信息
        private String purchaserName;
        private String purchaserCreditCode;
        private String purchaserBankName;
        private String purchaserBankAccount;
        private String purchaserPhone;
        private String purchaserAddress;
        // 开票类型
        private String invoiceType;            // NORMAL | SPECIAL | ELECTRONIC
        // 明细 JSON 字符串 [{name,spec,unit,qty,unitPrice,amount,taxRate,taxAmount}]
        private String itemsJson;
        // 业务标识
        private Boolean isArchitecture;
        private Boolean isTransProvince;
        private String productLocation;
        // 备注
        private String requestRemark;
    }
}
