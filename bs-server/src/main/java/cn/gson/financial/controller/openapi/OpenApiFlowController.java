package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.service.FlowService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 开放 API — flow 新模型 CRUD + 回写/查状态（FN-FLOW-02）。前缀 {@code /openapi/v1/flow}，免鉴权（@SaIgnore）。
 * <p>BT 本地 RPA 执行 → 回写 flow_*；本控制器薄包 {@link FlowService}（幂等 upsert，键即各表 UNIQUE）。
 * <ul>
 *   <li>{@code POST /flow/period}    upsert 主轴（建行 + 元信息）；{@code GET /flow/period} 查主轴 + 三类明细</li>
 *   <li>{@code POST /flow/declare-item} / {@code /payment-item} / {@code /collect-item}  upsert 明细（回写状态/金额/截图/回执/last_run_id）</li>
 *   <li>{@code POST /flow/run}（开跑插 running）、{@code POST /flow/run/{id}/finish}（跑完置终态）、{@code GET /flow/run} 查流水</li>
 * </ul>
 * <p>st_* 聚合 + 预申报 confirm gate 见 FN-FLOW-03。<b>彻底独立</b>：不碰 task_period、零 oc_task。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/flow")
@SaIgnore
public class OpenApiFlowController {

    private final FlowService flowService;

    // ═══════════════════════ period ═══════════════════════

    @PostMapping("/period")
    public JsonResult upsertPeriod(@RequestBody PeriodUpsertReq req) {
        String err = requireAccPeriod(req == null ? null : req.getAccountSetsId(), req == null ? null : req.getPeriod());
        if (err != null) return JsonResult.failure(err);
        try {
            return JsonResult.successful(toPeriodMap(flowService.upsertPeriod(req)));
        } catch (Exception e) {
            log.error("[FN-FLOW] upsertPeriod 失败 acc={} period={}", req.getAccountSetsId(), req.getPeriod(), e);
            return JsonResult.failure("flow period upsert 失败：" + e.getMessage());
        }
    }

    @GetMapping("/period")
    public JsonResult getPeriod(@RequestParam Long accountSetsId, @RequestParam String period) {
        String err = requireAccPeriod(accountSetsId, period);
        if (err != null) return JsonResult.failure(err);
        FlowPeriod p = flowService.findPeriod(accountSetsId, period);
        if (p == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("period", null);
            data.put("exists", false);
            return JsonResult.successful(data);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", toPeriodMap(p));
        data.put("exists", true);
        data.put("declareItems", mapList(flowService.listDeclareItems(p.getId()), this::toDeclareMap));
        data.put("paymentItems", mapList(flowService.listPaymentItems(p.getId()), this::toPaymentMap));
        data.put("collectItems", mapList(flowService.listCollectItems(p.getId()), this::toCollectMap));
        return JsonResult.successful(data);
    }

    // ═══════════════════════ declare-item ═══════════════════════

    @PostMapping("/declare-item")
    public JsonResult upsertDeclareItem(@RequestBody DeclareItemReq req) {
        String err = requireAccPeriod(req == null ? null : req.getAccountSetsId(), req == null ? null : req.getPeriod());
        if (err != null) return JsonResult.failure(err);
        if (blank(req.getCategory())) return JsonResult.failure("category 不能为空");
        if (blank(req.getTaxName())) return JsonResult.failure("taxName 不能为空");
        try {
            return JsonResult.successful(toDeclareMap(flowService.upsertDeclareItem(req)));
        } catch (Exception e) {
            log.error("[FN-FLOW] upsertDeclareItem 失败", e);
            return JsonResult.failure("declare-item upsert 失败：" + e.getMessage());
        }
    }

    @GetMapping("/declare-item")
    public JsonResult listDeclareItems(@RequestParam Long accountSetsId, @RequestParam String period) {
        String err = requireAccPeriod(accountSetsId, period);
        if (err != null) return JsonResult.failure(err);
        FlowPeriod p = flowService.findPeriod(accountSetsId, period);
        List<Map<String, Object>> rows = p == null ? List.of()
                : mapList(flowService.listDeclareItems(p.getId()), this::toDeclareMap);
        return JsonResult.successful(rows);
    }

    // ═══════════════════════ payment-item ═══════════════════════

    @PostMapping("/payment-item")
    public JsonResult upsertPaymentItem(@RequestBody PaymentItemReq req) {
        String err = requireAccPeriod(req == null ? null : req.getAccountSetsId(), req == null ? null : req.getPeriod());
        if (err != null) return JsonResult.failure(err);
        if (blank(req.getTaxCategory())) return JsonResult.failure("taxCategory 不能为空");
        if (blank(req.getTaxName())) return JsonResult.failure("taxName 不能为空");
        try {
            return JsonResult.successful(toPaymentMap(flowService.upsertPaymentItem(req)));
        } catch (Exception e) {
            log.error("[FN-FLOW] upsertPaymentItem 失败", e);
            return JsonResult.failure("payment-item upsert 失败：" + e.getMessage());
        }
    }

    @GetMapping("/payment-item")
    public JsonResult listPaymentItems(@RequestParam Long accountSetsId, @RequestParam String period) {
        String err = requireAccPeriod(accountSetsId, period);
        if (err != null) return JsonResult.failure(err);
        FlowPeriod p = flowService.findPeriod(accountSetsId, period);
        List<Map<String, Object>> rows = p == null ? List.of()
                : mapList(flowService.listPaymentItems(p.getId()), this::toPaymentMap);
        return JsonResult.successful(rows);
    }

    // ═══════════════════════ collect-item ═══════════════════════

    @PostMapping("/collect-item")
    public JsonResult upsertCollectItem(@RequestBody CollectItemReq req) {
        String err = requireAccPeriod(req == null ? null : req.getAccountSetsId(), req == null ? null : req.getPeriod());
        if (err != null) return JsonResult.failure(err);
        if (blank(req.getSource())) return JsonResult.failure("source 不能为空（invoice_out/invoice_in/bank/salary）");
        try {
            return JsonResult.successful(toCollectMap(flowService.upsertCollectItem(req)));
        } catch (Exception e) {
            log.error("[FN-FLOW] upsertCollectItem 失败", e);
            return JsonResult.failure("collect-item upsert 失败：" + e.getMessage());
        }
    }

    @GetMapping("/collect-item")
    public JsonResult listCollectItems(@RequestParam Long accountSetsId, @RequestParam String period) {
        String err = requireAccPeriod(accountSetsId, period);
        if (err != null) return JsonResult.failure(err);
        FlowPeriod p = flowService.findPeriod(accountSetsId, period);
        List<Map<String, Object>> rows = p == null ? List.of()
                : mapList(flowService.listCollectItems(p.getId()), this::toCollectMap);
        return JsonResult.successful(rows);
    }

    // ═══════════════════════ FN-FLOW-03：聚合 + 预申报 gate ═══════════════════════

    /** 手动/补算：重算主轴 11 个 st_*（明细最差值 / flow_run 终态）。写入接口已自动触发，此端点用于补算。 */
    @PostMapping("/period/recompute")
    public JsonResult recompute(@RequestParam Long accountSetsId, @RequestParam String period) {
        String err = requireAccPeriod(accountSetsId, period);
        if (err != null) return JsonResult.failure(err);
        FlowPeriod p = flowService.recomputeStagesByAccPeriod(accountSetsId, period);
        if (p == null) return JsonResult.failure("flow_period 不存在：accountSetsId=" + accountSetsId + " period=" + period);
        return JsonResult.successful(toPeriodMap(p));
    }

    /** 预申报财务确认 gate（报税前闸门）：写 predeclare_confirmed/_at/_by。confirmed 缺省 true。 */
    @PostMapping("/period/{id}/predeclare-confirm")
    public JsonResult predeclareConfirm(@PathVariable Long id, @RequestBody(required = false) PredeclareConfirmReq req) {
        if (id == null) return JsonResult.failure("id 不能为空");
        Boolean confirmed = req != null ? req.getConfirmed() : null;
        String by = req != null ? req.getBy() : null;
        try {
            return JsonResult.successful(toPeriodMap(flowService.confirmPredeclare(id, confirmed, by)));
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("[FN-FLOW] predeclareConfirm 失败 id={}", id, e);
            return JsonResult.failure("预申报确认失败：" + e.getMessage());
        }
    }

    // ═══════════════════════ run ═══════════════════════

    @PostMapping("/run")
    public JsonResult startRun(@RequestBody RunStartReq req) {
        String err = requireAccPeriod(req == null ? null : req.getAccountSetsId(), req == null ? null : req.getPeriod());
        if (err != null) return JsonResult.failure(err);
        if (blank(req.getStage())) return JsonResult.failure("stage 不能为空");
        try {
            return JsonResult.successful(toRunMap(flowService.startRun(req)));
        } catch (Exception e) {
            log.error("[FN-FLOW] startRun 失败", e);
            return JsonResult.failure("flow run start 失败：" + e.getMessage());
        }
    }

    @PostMapping("/run/{id}/finish")
    public JsonResult finishRun(@PathVariable Long id, @RequestBody(required = false) RunFinishReq req) {
        if (id == null) return JsonResult.failure("id 不能为空");
        try {
            return JsonResult.successful(toRunMap(flowService.finishRun(id, req != null ? req : new RunFinishReq())));
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("[FN-FLOW] finishRun 失败 id={}", id, e);
            return JsonResult.failure("flow run finish 失败：" + e.getMessage());
        }
    }

    @GetMapping("/run")
    public JsonResult listRuns(@RequestParam Long accountSetsId, @RequestParam String period,
                               @RequestParam(required = false) String stage) {
        String err = requireAccPeriod(accountSetsId, period);
        if (err != null) return JsonResult.failure(err);
        FlowPeriod p = flowService.findPeriod(accountSetsId, period);
        List<Map<String, Object>> rows = p == null ? List.of()
                : mapList(flowService.listRuns(p.getId(), stage), this::toRunMap);
        return JsonResult.successful(rows);
    }

    // ═══════════════════════ 校验 / 序列化 ═══════════════════════

    private static String requireAccPeriod(Long accountSetsId, String period) {
        if (accountSetsId == null) return "accountSetsId 不能为空";
        if (blank(period)) return "period 不能为空（格式 YYYY-MM）";
        return null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private <T> List<Map<String, Object>> mapList(List<T> list, java.util.function.Function<T, Map<String, Object>> f) {
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (T t : list) out.add(f.apply(t));
        return out;
    }

    private Map<String, Object> toPeriodMap(FlowPeriod p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("accountSetId", p.getAccountSetId());
        m.put("period", p.getPeriod());
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("login", p.getStLogin());
        st.put("verify", p.getStVerify());
        st.put("init", p.getStInit());
        st.put("collect", p.getStCollect());
        st.put("book", p.getStBook());
        st.put("carry", p.getStCarry());
        st.put("predeclare", p.getStPredeclare());
        st.put("declareVat", p.getStDeclareVat());
        st.put("declareIit", p.getStDeclareIit());
        st.put("payment", p.getStPayment());
        st.put("archive", p.getStArchive());
        m.put("st", st);
        m.put("predeclareConfirmed", Boolean.TRUE.equals(p.getPredeclareConfirmed()));
        m.put("predeclareConfirmedAt", p.getPredeclareConfirmedAt());
        m.put("predeclareConfirmedBy", p.getPredeclareConfirmedBy());
        m.put("checksJson", p.getChecksJson());
        m.put("currentStage", p.getCurrentStage());
        m.put("errorStage", p.getErrorStage());
        m.put("errorMsg", p.getErrorMsg());
        m.put("createdAt", p.getCreatedAt());
        m.put("updatedAt", p.getUpdatedAt());
        return m;
    }

    private Map<String, Object> toDeclareMap(FlowDeclareItem it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", it.getId());
        m.put("flowPeriodId", it.getFlowPeriodId());
        m.put("accountSetId", it.getAccountSetId());
        m.put("period", it.getPeriod());
        m.put("category", it.getCategory());
        m.put("taxName", it.getTaxName());
        m.put("declarePeriod", it.getDeclarePeriod());
        m.put("declareDeadline", it.getDeclareDeadline());
        m.put("dryRun", Boolean.TRUE.equals(it.getDryRun()));
        m.put("status", it.getStatus());
        m.put("declaredAmount", it.getDeclaredAmount());
        m.put("receiptNo", it.getReceiptNo());
        m.put("resultJson", it.getResultJson());
        m.put("screenshotUrls", it.getScreenshotUrls());
        m.put("errorMsg", it.getErrorMsg());
        m.put("lastRunId", it.getLastRunId());
        m.put("executedAt", it.getExecutedAt());
        m.put("updatedAt", it.getUpdatedAt());
        return m;
    }

    private Map<String, Object> toPaymentMap(FlowPaymentItem it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", it.getId());
        m.put("flowPeriodId", it.getFlowPeriodId());
        m.put("accountSetId", it.getAccountSetId());
        m.put("period", it.getPeriod());
        m.put("taxCategory", it.getTaxCategory());
        m.put("taxName", it.getTaxName());
        m.put("taxAmount", it.getTaxAmount());
        m.put("paidAmount", it.getPaidAmount());
        m.put("unpaidAmount", it.getUnpaidAmount());
        m.put("payStatus", it.getPayStatus());
        m.put("payDeadline", it.getPayDeadline());
        m.put("receiptNo", it.getReceiptNo());
        m.put("screenshotUrls", it.getScreenshotUrls());
        m.put("lastRunId", it.getLastRunId());
        m.put("queriedAt", it.getQueriedAt());
        m.put("updatedAt", it.getUpdatedAt());
        return m;
    }

    private Map<String, Object> toCollectMap(FlowCollectItem it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", it.getId());
        m.put("flowPeriodId", it.getFlowPeriodId());
        m.put("accountSetId", it.getAccountSetId());
        m.put("period", it.getPeriod());
        m.put("source", it.getSource());
        m.put("status", it.getStatus());
        m.put("itemCount", it.getItemCount());
        m.put("resultJson", it.getResultJson());
        m.put("screenshotUrls", it.getScreenshotUrls());
        m.put("errorMsg", it.getErrorMsg());
        m.put("lastRunId", it.getLastRunId());
        m.put("executedAt", it.getExecutedAt());
        m.put("updatedAt", it.getUpdatedAt());
        return m;
    }

    private Map<String, Object> toRunMap(FlowRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("flowPeriodId", r.getFlowPeriodId());
        m.put("accountSetId", r.getAccountSetId());
        m.put("period", r.getPeriod());
        m.put("stage", r.getStage());
        m.put("refType", r.getRefType());
        m.put("refId", r.getRefId());
        m.put("attemptNo", r.getAttemptNo());
        m.put("dryRun", Boolean.TRUE.equals(r.getDryRun()));
        m.put("status", r.getStatus());
        m.put("startedAt", r.getStartedAt());
        m.put("endedAt", r.getEndedAt());
        m.put("resultJson", r.getResultJson());
        m.put("screenshotUrls", r.getScreenshotUrls());
        m.put("logExcerpt", r.getLogExcerpt());
        m.put("errorMsg", r.getErrorMsg());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    // ═══════════════════════ DTO ═══════════════════════

    @Data
    public static class PeriodUpsertReq {
        private Long accountSetsId;
        private String period;
        private String checksJson;
        private String currentStage;
        private String errorStage;
        private String errorMsg;
    }

    @Data
    public static class DeclareItemReq {
        private Long accountSetsId;
        private String period;
        private String category;
        private String taxName;
        private String declarePeriod;
        private LocalDate declareDeadline;
        private Boolean dryRun;
        private String status;
        private BigDecimal declaredAmount;
        private String receiptNo;
        private String resultJson;
        private String screenshotUrls;
        private String errorMsg;
        private Long lastRunId;
        private LocalDateTime executedAt;
    }

    @Data
    public static class PaymentItemReq {
        private Long accountSetsId;
        private String period;
        private String taxCategory;
        private String taxName;
        private BigDecimal taxAmount;
        private BigDecimal paidAmount;
        private BigDecimal unpaidAmount;
        private String payStatus;
        private LocalDate payDeadline;
        private String receiptNo;
        private String screenshotUrls;
        private Long lastRunId;
        private LocalDateTime queriedAt;
    }

    @Data
    public static class CollectItemReq {
        private Long accountSetsId;
        private String period;
        private String source;
        private String status;
        private Integer itemCount;
        private String resultJson;
        private String screenshotUrls;
        private String errorMsg;
        private Long lastRunId;
        private LocalDateTime executedAt;
    }

    @Data
    public static class RunStartReq {
        private Long accountSetsId;
        private String period;
        private String stage;
        private String refType;
        private Long refId;
        private Integer attemptNo;
        private Boolean dryRun;
    }

    @Data
    public static class RunFinishReq {
        private String status;
        private String resultJson;
        private String screenshotUrls;
        private String logExcerpt;
        private String errorMsg;
    }

    @Data
    public static class PredeclareConfirmReq {
        /** 缺省 true；false=撤销确认 */
        private Boolean confirmed;
        /** 确认人标识（BT 传入或系统标识） */
        private String by;
    }
}
