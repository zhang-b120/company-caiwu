package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.OcTask;
import cn.gson.financial.kernel.model.repo.OcTaskRepository;
import cn.gson.financial.task.workbench.service.TaskWorkbenchService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 开放 API — 申报任务<b>触发 + 查状态</b>（FN-NEW-DECLARE-TRIGGER-API，BT-NEW-DECLARE-VIA-TAX-ORCHESTRATOR §2 配套）。
 * 路径前缀 {@code /openapi/v1/declare}（{@code /trigger}、{@code /status}）。免鉴权（@SaIgnore）。
 *
 * <p><b>照抄 {@code OpenApiInvoiceCollectTriggerController} 成熟样板</b>，差异：
 * 发票采集走 {@code taskWorkbenchService.dispatch}（step 机制建 4 条 download_invoice_*）；
 * 申报触发走 {@link TaskWorkbenchService#dispatchDeclareTask}（按 taskType 建 1 条申报 OcTask，
 * payload 含 province/tax_no(=credit_code)/period + 默认办税员登录凭证，对齐 OC {@code fetch_task} 契约
 * tax_orchestrator.py:4391-4430）。
 *
 * <p>管线：BT trigger → 建 queued OcTask → OC 轮询 {@code /oc/tasks/next-*} 抢单执行 →
 * {@code /oc/callback} 回写 result_json/截图（current_declaration_query 走
 * {@code handleDeclarationQueryCallback}，vat_declare* 走各自既有 handler）→ 本控制器 status 可见。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/declare")
@SaIgnore
public class OpenApiDeclareTriggerController {

    private final TaskWorkbenchService taskWorkbenchService;
    private final OcTaskRepository ocTaskRepository;

    // ═══════════════════════════════════════════════════════════════
    // POST /openapi/v1/declare/trigger
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/trigger")
    public JsonResult trigger(@RequestBody TriggerReq req) {
        if (req == null || req.getAccountSetsId() == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (req.getPeriod() == null || req.getPeriod().isBlank()) {
            return JsonResult.failure("period 不能为空（格式 YYYY-MM）");
        }
        if (req.getTaskType() == null || req.getTaskType().isBlank()) {
            return JsonResult.failure("taskType 不能为空（合法值："
                    + String.join("|", TaskWorkbenchService.DECLARE_TASK_TYPES) + "）");
        }
        try {
            Map<String, Object> r = taskWorkbenchService.dispatchDeclareTask(
                    req.getAccountSetsId(), req.getPeriod().trim(), req.getTaskType().trim(),
                    req.getTaxTypes(), req.getOcInstanceId());
            return JsonResult.successful(r);
        } catch (IllegalArgumentException e) {
            // 入参非法 / 账套不存在
            return JsonResult.failure(e.getMessage());
        } catch (IllegalStateException e) {
            // 凭证缺失 / 重复触发（业务拒绝）
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("[FN-NEW-DECLARE-TRIGGER] trigger 失败 accountSetsId={} period={} taskType={}",
                    req.getAccountSetsId(), req.getPeriod(), req.getTaskType(), e);
            return JsonResult.failure("触发申报失败：" + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // GET /openapi/v1/declare/status?accountSetsId=&period=
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/status")
    public JsonResult status(@RequestParam Long accountSetsId, @RequestParam String period) {
        if (accountSetsId == null) return JsonResult.failure("accountSetsId 不能为空");
        if (period == null || period.isBlank()) return JsonResult.failure("period 不能为空（格式 YYYY-MM）");

        List<OcTask> tasks = ocTaskRepository.findByAccountSetIdAndPeriodAndTaskTypeIn(
                accountSetsId, period.trim(), TaskWorkbenchService.DECLARE_TASK_TYPES);

        List<Map<String, Object>> taskList = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        for (OcTask t : tasks) {
            statuses.add(t.getStatus());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ocTaskId", t.getId());
            m.put("taskType", t.getTaskType());
            m.put("status", t.getStatus());
            m.put("errorMsg", extractError(t));
            m.put("resultSummary", extractResultSummary(t));
            m.put("screenshotUrls", extractScreenshotUrls(t));
            m.put("updatedAt", t.getFinishedAt() != null ? t.getFinishedAt() : t.getCreatedAt());
            taskList.add(m);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("overall", deriveOverall(statuses));
        data.put("tasks", taskList);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 纯逻辑 / 解析（与 InvoiceCollectTrigger 同款，便于单测）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 申报类 OcTask 状态聚合为 overall（同 invoice-collect/status 口径）。
     * 无任务 → none；有 running → running；有 queued/pending/blocked（或未知态）→ queued；
     * 全终态：全 done → done；全 failed → failed；done/failed 混 → partial_failed。
     */
    static String deriveOverall(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) return "none";
        boolean hasRunning = false, hasQueued = false, hasDone = false, hasFailed = false, hasOther = false;
        for (String s : statuses) {
            String st = s == null ? "" : s;
            switch (st) {
                case "running" -> hasRunning = true;
                case "queued", "pending", "blocked" -> hasQueued = true;
                case "done" -> hasDone = true;
                case "failed" -> hasFailed = true;
                default -> hasOther = true;
            }
        }
        if (hasRunning) return "running";
        if (hasQueued || hasOther) return "queued";
        if (hasDone && hasFailed) return "partial_failed";
        if (hasFailed) return "failed";
        return "done";
    }

    /** 从 OcTask.resultJson 尽力提取错误信息（仅 failed 任务，解析失败返 null）。 */
    private static String extractError(OcTask t) {
        if (!"failed".equals(t.getStatus())) return null;
        JSONObject o = parseResult(t);
        if (o == null) return null;
        for (String k : new String[]{"errorMsg", "error_msg", "error", "message", "msg"}) {
            String v = o.getString(k);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /** result 摘要：优先 result_json 的 {@code result} 子对象，否则回退整包（解析失败返 null）。 */
    private static Object extractResultSummary(OcTask t) {
        JSONObject o = parseResult(t);
        if (o == null) return null;
        if (o.containsKey("result")) return o.get("result");
        return o;
    }

    /** 截图 URL 列表：result_json.screenshot_urls 优先，回退 OcTask.screenshotUrl 单值，无则空数组。 */
    private static List<String> extractScreenshotUrls(OcTask t) {
        JSONObject o = parseResult(t);
        if (o != null) {
            Object urls = o.get("screenshot_urls");
            if (urls instanceof List<?> l) {
                List<String> out = new ArrayList<>();
                for (Object u : l) if (u != null) out.add(u.toString());
                if (!out.isEmpty()) return out;
            }
        }
        if (t.getScreenshotUrl() != null && !t.getScreenshotUrl().isBlank()) {
            return List.of(t.getScreenshotUrl());
        }
        return Collections.emptyList();
    }

    private static JSONObject parseResult(OcTask t) {
        String rj = t.getResultJson();
        if (rj == null || rj.isBlank()) return null;
        try {
            return JSON.parseObject(rj);
        } catch (Exception e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // DTO
    // ═══════════════════════════════════════════════════════════════

    @Data
    public static class TriggerReq {
        private Long accountSetsId;
        /** 账期 YYYY-MM */
        private String period;
        /** 申报任务类型：current_declaration_query|vat_declare|vat_declare_pre|vat_declare_post */
        private String taskType;
        /** 可选：税种清单，并入 payload.tax_types 供 OC 逐税种申报 */
        private List<String> taxTypes;
        /** 可选：指定 OC 实例 */
        private String ocInstanceId;
    }
}
