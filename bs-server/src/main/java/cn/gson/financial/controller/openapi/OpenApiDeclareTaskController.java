package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.entity.DeclareTask;
import cn.gson.financial.kernel.model.enums.DeclareTaskStatus;
import cn.gson.financial.kernel.service.DeclareTaskService;
import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 开放 API — 申报任务（FN-03）
 * <p>
 * 提供给 brower-tax 团队（BT-10/11/12/13）做批量增值税/个税申报调度。全部接口免鉴权（@SaIgnore）。
 * 路径前缀 /openapi/v1/declare-task
 * <p>
 * 状态机：PENDING → PREPARING → EXECUTING → SUCCESS / FAILED / MANUAL
 *
 * <ul>
 *   <li>POST /                 创建单个任务</li>
 *   <li>GET  /?status=&customerId=&accountSetsId=&page=&size=    分页查询</li>
 *   <li>GET  /{id}             详情</li>
 *   <li>PUT  /{id}/claim       PENDING → PREPARING（乐观锁，重复领取返回 ALREADY_CLAIMED）</li>
 *   <li>PUT  /{id}/start       PREPARING → EXECUTING</li>
 *   <li>POST /{id}/result      EXECUTING → SUCCESS/FAILED/MANUAL</li>
 *   <li>POST /batch            批量创建（每条独立事务）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/declare-task")
@SaIgnore
public class OpenApiDeclareTaskController {

    private final DeclareTaskService declareTaskService;

    // ═══════════════════════════════════════════════════════════════
    // 1. 创建单个任务
    // ═══════════════════════════════════════════════════════════════

    @PostMapping
    public JsonResult create(@RequestBody DeclareTaskReq req) {
        String err = validate(req);
        if (err != null) {
            return failureWithType(err, DeclareTaskService.ERROR_INVALID_STATUS);
        }
        try {
            DeclareTask saved = declareTaskService.save(toEntity(req, null));
            return JsonResult.successful(toDto(saved));
        } catch (ServiceException e) {
            return errorResult(e);
        } catch (Exception e) {
            log.error("openapi declare-task 创建失败", e);
            return JsonResult.failure(e.getMessage() != null ? e.getMessage() : "创建失败");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. 列表查询（分页）
    // ═══════════════════════════════════════════════════════════════

    @GetMapping
    public JsonResult list(@RequestParam(required = false) String status,
                           @RequestParam(required = false) Long customerId,
                           @RequestParam(required = false) Long accountSetsId,
                           @RequestParam(required = false) Integer page,
                           @RequestParam(required = false) Integer size) {
        DeclareTaskStatus statusEnum = null;
        if (StrUtil.isNotBlank(status)) {
            try {
                statusEnum = DeclareTaskStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return failureWithType("非法 status: " + status, DeclareTaskService.ERROR_INVALID_STATUS);
            }
        }
        int p = page != null ? page : 1;
        int s = size != null ? size : 50;

        Page<DeclareTask> pr = declareTaskService.page(statusEnum, customerId, accountSetsId, p, s);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (DeclareTask t : pr.getContent()) {
            rows.add(toDto(t));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", rows);
        // 用 int 包装绕过全局 Long→String 序列化器，保证 total 是 number
        data.put("total", (int) pr.getTotalElements());
        data.put("page", p);
        data.put("size", s);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. 详情
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/{id}")
    public JsonResult detail(@PathVariable Long id) {
        return declareTaskService.findById(id)
                .map(t -> JsonResult.successful(toDto(t)))
                .orElseGet(() -> failureWithType("申报任务不存在: " + id, DeclareTaskService.ERROR_NOT_FOUND));
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. claim：PENDING → PREPARING（乐观锁）
    // ═══════════════════════════════════════════════════════════════

    @PutMapping("/{id}/claim")
    public JsonResult claim(@PathVariable Long id, @RequestBody(required = false) ClaimReq req) {
        String executor = req != null ? req.getExecutor() : null;
        if (StrUtil.isBlank(executor)) {
            return failureWithType("executor 不能为空", DeclareTaskService.ERROR_INVALID_STATUS);
        }
        try {
            DeclareTask t = declareTaskService.claim(id, executor);
            return JsonResult.successful(toDto(t));
        } catch (ServiceException e) {
            return errorResult(e);
        } catch (Exception e) {
            log.error("openapi declare-task claim 失败 id={}", id, e);
            return JsonResult.failure(e.getMessage() != null ? e.getMessage() : "claim 失败");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. start：PREPARING → EXECUTING
    // ═══════════════════════════════════════════════════════════════

    @PutMapping("/{id}/start")
    public JsonResult start(@PathVariable Long id) {
        try {
            DeclareTask t = declareTaskService.start(id);
            return JsonResult.successful(toDto(t));
        } catch (ServiceException e) {
            return errorResult(e);
        } catch (Exception e) {
            log.error("openapi declare-task start 失败 id={}", id, e);
            return JsonResult.failure(e.getMessage() != null ? e.getMessage() : "start 失败");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 6. result：EXECUTING → SUCCESS / FAILED / MANUAL
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/{id}/result")
    public JsonResult result(@PathVariable Long id, @RequestBody ResultReq req) {
        if (req == null || StrUtil.isBlank(req.getStatus())) {
            return failureWithType("status 不能为空", DeclareTaskService.ERROR_INVALID_STATUS);
        }
        DeclareTaskStatus targetStatus;
        try {
            targetStatus = DeclareTaskStatus.valueOf(req.getStatus().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return failureWithType("非法 status: " + req.getStatus(), DeclareTaskService.ERROR_INVALID_STATUS);
        }
        try {
            DeclareTask t = declareTaskService.completeWithResult(
                    id, targetStatus, req.getResultJson(), req.getErrorMsg());
            return JsonResult.successful(toDto(t));
        } catch (ServiceException e) {
            return errorResult(e);
        } catch (Exception e) {
            log.error("openapi declare-task result 失败 id={}", id, e);
            return JsonResult.failure(e.getMessage() != null ? e.getMessage() : "result 失败");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 7. 批量创建（每条独立事务，参考 OpenApiVoucherController.batchPartial）
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/batch")
    public JsonResult batch(@RequestBody BatchReq req) {
        if (req == null || req.getTasks() == null || req.getTasks().isEmpty()) {
            return JsonResult.failure("tasks 不能为空");
        }
        List<DeclareTaskReq> items = req.getTasks();

        List<Map<String, Object>> results = new ArrayList<>(items.size());
        int succeeded = 0;
        int failed = 0;
        for (int i = 0; i < items.size(); i++) {
            DeclareTaskReq item = items.get(i);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("index", i);
            try {
                String err = validate(item);
                if (err != null) {
                    throw DeclareTaskService.bizError(DeclareTaskService.ERROR_INVALID_STATUS, err);
                }
                // 通过 spring 自身代理拿到 REQUIRES_NEW 事务
                DeclareTask saved = declareTaskService.saveInNewTransaction(toEntity(item, null));
                r.put("status", "ok");
                r.put("id", saved.getId());
                succeeded++;
            } catch (ServiceException e) {
                r.put("status", "error");
                r.put("errorType", firstNonNull(
                        DeclareTaskService.extractErrorType(e.getMessage()),
                        DeclareTaskService.ERROR_INVALID_STATUS));
                r.put("message", DeclareTaskService.stripErrorType(e.getMessage()));
                failed++;
            } catch (Exception e) {
                r.put("status", "error");
                r.put("errorType", "INTERNAL_ERROR");
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

    private static String validate(DeclareTaskReq req) {
        if (req == null) return "请求体不能为空";
        if (req.getAccountSetsId() == null) return "accountSetsId 不能为空";
        if (req.getCustomerId() == null) return "customerId 不能为空";
        if (StrUtil.isBlank(req.getTaxType())) return "taxType 不能为空";
        if (req.getPeriodYear() == null) return "periodYear 不能为空";
        if (req.getPeriodMonth() == null && req.getPeriodQuarter() == null) {
            return "periodMonth 与 periodQuarter 必须至少有一个";
        }
        if (req.getPeriodMonth() != null && (req.getPeriodMonth() < 1 || req.getPeriodMonth() > 12)) {
            return "periodMonth 必须在 1-12 之间";
        }
        if (req.getPeriodQuarter() != null && (req.getPeriodQuarter() < 1 || req.getPeriodQuarter() > 4)) {
            return "periodQuarter 必须在 1-4 之间";
        }
        return null;
    }

    private static DeclareTask toEntity(DeclareTaskReq req, DeclareTask base) {
        DeclareTask t = base != null ? base : new DeclareTask();
        t.setAccountSetsId(req.getAccountSetsId());
        t.setCustomerId(req.getCustomerId());
        t.setTaxType(req.getTaxType().trim().toUpperCase());
        t.setPeriodYear(req.getPeriodYear());
        t.setPeriodMonth(req.getPeriodMonth());
        t.setPeriodQuarter(req.getPeriodQuarter());
        t.setProvince(req.getProvince());
        t.setDryRun(req.getDryRun() != null ? req.getDryRun() : Boolean.FALSE);
        t.setExecutor(req.getExecutor());
        // status 默认 PENDING（entity 默认值）；不允许调用方指定初始状态
        if (t.getStatus() == null) {
            t.setStatus(DeclareTaskStatus.PENDING);
        }
        return t;
    }

    private static Map<String, Object> toDto(DeclareTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("accountSetsId", t.getAccountSetsId());
        m.put("customerId", t.getCustomerId());
        m.put("taxType", t.getTaxType());
        m.put("periodYear", t.getPeriodYear());
        m.put("periodMonth", t.getPeriodMonth());
        m.put("periodQuarter", t.getPeriodQuarter());
        m.put("province", t.getProvince());
        m.put("status", t.getStatus() != null ? t.getStatus().name() : null);
        m.put("executor", t.getExecutor());
        m.put("dryRun", Boolean.TRUE.equals(t.getDryRun()));
        m.put("resultJson", t.getResultJson());
        m.put("errorMsg", t.getErrorMsg());
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

    private static JsonResult errorResult(ServiceException e) {
        String type = DeclareTaskService.extractErrorType(e.getMessage());
        String pure = DeclareTaskService.stripErrorType(e.getMessage());
        return failureWithType(pure != null ? pure : "operation failed",
                type != null ? type : "INTERNAL_ERROR");
    }

    private static <T> T firstNonNull(T a, T b) {
        return a != null ? a : b;
    }

    // ═══════════════════════════════════════════════════════════════
    // 请求 DTO
    // ═══════════════════════════════════════════════════════════════

    @Data
    public static class DeclareTaskReq {
        private Long accountSetsId;
        private Long customerId;
        private String taxType;
        private Integer periodYear;
        private Integer periodMonth;
        private Integer periodQuarter;
        private String province;
        private Boolean dryRun;
        private String executor;
    }

    @Data
    public static class ClaimReq {
        private String executor;
    }

    @Data
    public static class ResultReq {
        private String status;
        private String resultJson;
        private String errorMsg;
    }

    @Data
    public static class BatchReq {
        private List<DeclareTaskReq> tasks;
    }
}
