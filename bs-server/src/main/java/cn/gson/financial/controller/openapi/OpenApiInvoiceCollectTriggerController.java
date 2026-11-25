package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.OcTask;
import cn.gson.financial.kernel.model.entity.QInvoice;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.query.InvoicePeriodPredicate;
import cn.gson.financial.kernel.model.repo.OcTaskRepository;
import cn.gson.financial.task.workbench.service.TaskWorkbenchService;
import cn.gson.financial.timedTasks.service.TimedTaskService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.querydsl.core.types.dsl.BooleanExpression;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 开放 API — 发票<b>下载采集</b>触发 + 查状态（INVOICE-COLLECT-TRIGGER-WIRE 改造 A）。
 * 路径前缀 /openapi/v1/invoice-collect（{@code /trigger}、{@code /status}）。
 *
 * <p><b>⚠️ 与 {@code OpenApiInvoiceCollectController}（发票<b>开具</b>申请：collect/{orderNo}/claim/result，
 * 不同领域）共用同一 base 路径但子路径不冲突</b>：本控制器只挂 {@code POST /trigger} 与 {@code GET /status}，
 * 与对方的 {@code POST /}、{@code GET /{orderNo}} 等不重复（Spring 精确路径 {@code /status} 优先于 {@code /{orderNo}}）。
 * 命名重叠属跨团队历史，详见完成情况备注。
 *
 * <p><b>薄包，复用现有能力（严禁另写采集/入库/调度）</b>：
 * trigger 复用 {@link TaskWorkbenchService#dispatch}（step="invoice" → 创建 4 个 download_invoice_* OcTask；
 * 单 subType → 单项）；status 复用 {@link OcTaskRepository} 查 OcTask + {@link InvoicePeriodPredicate} 口径查 Invoice 计数。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/invoice-collect")
@SaIgnore
public class OpenApiInvoiceCollectTriggerController {

    private final TaskWorkbenchService taskWorkbenchService;
    private final OcTaskRepository ocTaskRepository;
    private final TimedTaskService timedTaskService;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QInvoice qInvoice = QInvoice.invoice;
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 4 个发票下载 OcTask 类型（dispatchInvoiceSubTasks / dataSubTypeToOcTaskType 产出）。 */
    static final List<String> DOWNLOAD_INVOICE_TYPES = List.of(
            "download_invoice_in", "download_invoice_out",
            "download_invoice_checked", "download_invoice_unchecked");

    // ═══════════════════════════════════════════════════════════════
    // A1  POST /openapi/v1/invoice-collect/trigger
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/trigger")
    public JsonResult trigger(@RequestBody TriggerReq req) {
        if (req == null || req.getAccountSetsId() == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (req.getPeriod() == null || req.getPeriod().isBlank()) {
            return JsonResult.failure("period 不能为空（格式 YYYY-MM）");
        }
        // openapi subType(in|out|checked|unchecked) → dispatch step；缺省 → "invoice"（四项）
        String step;
        String sub = req.getSubType() == null ? null : req.getSubType().trim();
        if (sub == null || sub.isEmpty()) {
            step = "invoice";
        } else {
            step = switch (sub) {
                case "in" -> "invoice_in";
                case "out" -> "invoice_out";
                case "checked" -> "invoice_checked";
                case "unchecked" -> "invoice_unchecked";
                default -> null;
            };
            if (step == null) {
                return JsonResult.failure("subType 非法（合法值：in|out|checked|unchecked，缺省=四项全采）");
            }
        }

        // FN-FIX-INVOICE-COLLECT-SMALLSCALE-PERIOD-NORMALIZE：小规模账套把 period 归一到季末月
        //（整季票/任务都落季末），使 BT 传季中月也对齐；一般纳税人原样。dispatch 内部对小规模本就落季末，
        // 传归一后 period 幂等且让单项(dispatchDataSubTask 不自动归一)也落季末，与入库口径一致。
        String effPeriod = effectivePeriod(req.getAccountSetsId(), req.getPeriod());

        Map<String, Object> result;
        try {
            // force=false：复用现有 dispatch；subType 已映射进 step，第 6 参传 null
            result = taskWorkbenchService.dispatch(req.getAccountSetsId(), effPeriod, step, false,
                    req.getOcInstanceId(), null);
        } catch (Exception e) {
            log.warn("invoice-collect/trigger dispatch 失败 accountSetsId={} period={}(eff={}): {}",
                    req.getAccountSetsId(), req.getPeriod(), effPeriod, e.getMessage());
            return JsonResult.failure("触发采集失败：" + e.getMessage());
        }

        boolean ok = Boolean.TRUE.equals(result.get("success"));
        if (!ok) {
            // 硬失败（未配置 OC 实例 / 正在执行 / 小规模无需勾选等）—— 透传原因
            return JsonResult.failure(str(result.get("msg"), "触发采集失败"));
        }

        // dispatched：四项路径返回 result.dispatched（[invoice_in...]）；单项成功则为 [sub]
        List<?> dispatched;
        Object dispatchedRaw = result.get("dispatched");
        if (dispatchedRaw instanceof List<?> l) {
            dispatched = l;
        } else {
            dispatched = sub != null ? List.of(sub) : Collections.emptyList();
        }
        boolean blocked = Boolean.TRUE.equals(result.get("blocked"));

        // ocTaskIds：dispatch 不返回 id，按归一后 period 查 OcTask 取（已落 queued/pending 的 4 类）
        List<Long> ocTaskIds = ocTaskRepository
                .findByAccountSetIdAndPeriodAndTaskTypeIn(req.getAccountSetsId(), effPeriod, DOWNLOAD_INVOICE_TYPES)
                .stream().map(OcTask::getId).toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dispatched", dispatched);
        data.put("blocked", blocked);
        data.put("blockedReason", blocked ? str(result.get("msg"), null) : null);
        data.put("ocTaskIds", ocTaskIds);
        data.put("effectivePeriod", effPeriod);   // 实际生效账期（小规模=季末月），便于 BT UI 展示
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // A2  GET /openapi/v1/invoice-collect/status?accountSetsId=&period=
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/status")
    public JsonResult status(@RequestParam Long accountSetsId, @RequestParam String period) {
        if (accountSetsId == null) return JsonResult.failure("accountSetsId 不能为空");
        if (period == null || period.isBlank()) return JsonResult.failure("period 不能为空（格式 YYYY-MM）");

        // FN-FIX-INVOICE-COLLECT-SMALLSCALE-PERIOD-NORMALIZE：小规模归一到季末月再查 OcTask/Invoice
        String effPeriod = effectivePeriod(accountSetsId, period);

        List<OcTask> tasks = ocTaskRepository
                .findByAccountSetIdAndPeriodAndTaskTypeIn(accountSetsId, effPeriod, DOWNLOAD_INVOICE_TYPES);

        List<Map<String, Object>> taskList = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        for (OcTask t : tasks) {
            statuses.add(t.getStatus());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskType", t.getTaskType());
            m.put("status", t.getStatus());
            m.put("errorMsg", extractError(t));
            m.put("updatedAt", t.getFinishedAt() != null ? t.getFinishedAt() : t.getCreatedAt());
            taskList.add(m);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("overall", deriveOverall(statuses));
        data.put("tasks", taskList);
        // 计数取已落库 Invoice（按账套 + 归属期 InvoicePeriodPredicate + type），让"采来的票=做账要用的票"。
        // 小规模整季票 assignmentDate 落季末（saveImportInvoiceSeason），按季末月查即得整季张数。
        data.put("inCount", countInvoice(accountSetsId, effPeriod, InvoiceType.IN));
        data.put("outCount", countInvoice(accountSetsId, effPeriod, InvoiceType.OUT));
        data.put("effectivePeriod", effPeriod);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 纯逻辑 / 可覆写钩子（便于单测）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 实际生效账期。FN-INVOICE-COLLECT-MODE-CONFIG：判据由 vatType==SMALL_SCALE 改为账套级采集模式
     * （{@code timedTaskService.isQuarterlyInvoiceCollect}，与 saveInvoice 分流统一口径，杜绝双判据）：
     * 季度模式（小规模默认）→ 归一到所在季度<b>季末月</b>；月度模式 / 一般纳税人 / 判定失败 → 原样按月。
     * package-private 便于单测覆写。
     */
    String effectivePeriod(Long accountSetsId, String period) {
        try {
            if (timedTaskService.isQuarterlyInvoiceCollect(accountSetsId)) {
                return quarterEndPeriod(period);
            }
        } catch (Exception e) {
            log.warn("invoice-collect 判定纳税人类型失败，按原 period accountSetsId={} period={}: {}",
                    accountSetsId, period, e.getMessage());
        }
        return period;
    }

    /** period(YYYY-MM) → 所在季度季末月(YYYY-MM)。Q1→03 / Q2→06 / Q3→09 / Q4→12。纯逻辑便于单测。 */
    static String quarterEndPeriod(String period) {
        YearMonth ym = YearMonth.parse(period, PERIOD_FMT);
        int quarterEndMonth = ((ym.getMonthValue() - 1) / 3) * 3 + 3;
        return YearMonth.of(ym.getYear(), quarterEndMonth).format(PERIOD_FMT);
    }

    /**
     * 4 个 download_invoice_* OcTask 状态聚合为 overall。
     * 无任务 → none；有 running → running；有 queued/pending（或未知态）→ queued；
     * 全终态：全 done → done；全 failed → failed；done/failed 混 → partial_failed。
     */
    static String deriveOverall(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) return "none";
        boolean hasRunning = false, hasQueued = false, hasDone = false, hasFailed = false, hasOther = false;
        for (String s : statuses) {
            String st = s == null ? "" : s;
            switch (st) {
                case "running" -> hasRunning = true;
                case "queued", "pending" -> hasQueued = true;
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

    /** 按账套 + 归属期（assignmentDate 优先 + tradeDate 兜底）+ type 统计已落库发票数（package-private 便于单测覆写）。 */
    int countInvoice(Long accountSetsId, String period, InvoiceType type) {
        try {
            YearMonth ym = YearMonth.parse(period, PERIOD_FMT);
            LocalDate start = ym.atDay(1);
            LocalDate end = ym.atEndOfMonth();
            BooleanExpression periodCond = InvoicePeriodPredicate.byPeriod(qInvoice, start, end);
            return (int) bqf.selectFrom(qInvoice)
                    .where(qInvoice.accountSetsId.eq(accountSetsId)
                            .and(qInvoice.type.eq(type))
                            .and(periodCond))
                    .fetchCount();
        } catch (Exception e) {
            log.warn("invoice-collect/status 统计发票数失败 accountSetsId={} period={} type={}: {}",
                    accountSetsId, period, type, e.getMessage());
            return 0;
        }
    }

    /** 从 OcTask.resultJson 尽力提取错误信息（OC 回写结构由采集端定义，解析失败返 null）。 */
    private static String extractError(OcTask t) {
        if (!"failed".equals(t.getStatus())) return null;
        String rj = t.getResultJson();
        if (rj == null || rj.isBlank()) return null;
        try {
            JSONObject o = JSON.parseObject(rj);
            for (String k : new String[]{"errorMsg", "error", "message", "msg"}) {
                String v = o.getString(k);
                if (v != null && !v.isBlank()) return v;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String str(Object o, String dft) {
        return o instanceof String s && !s.isBlank() ? s : dft;
    }

    // ═══════════════════════════════════════════════════════════════
    // DTO
    // ═══════════════════════════════════════════════════════════════

    @Data
    public static class TriggerReq {
        private Long accountSetsId;
        /** 账期 YYYY-MM */
        private String period;
        /** 指定 OC 实例（可空，缺省用账套绑定的） */
        private String ocInstanceId;
        /** 可空：in|out|checked|unchecked，缺省=四项全采 */
        private String subType;
    }
}
