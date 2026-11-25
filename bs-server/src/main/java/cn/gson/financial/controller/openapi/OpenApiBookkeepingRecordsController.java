package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.BookkeepingRunRecord;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.entity.VoucherReasoningRecord;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.repo.BookkeepingRunRecordRepository;
import cn.gson.financial.kernel.model.repo.VoucherReasoningRecordRepository;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 开放 API — 做账记录（FN-NEW-BOOKKEEPING-RUN-RECORD-PERSISTENCE）。
 * <p>路径前缀 {@code /openapi/v1/bookkeeping-records}。
 *
 * <ul>
 *   <li>GET /?accountSetsId=&period= → 列出该期所有做账记录（按 runAt desc）</li>
 *   <li>GET /{runId} → 完整详情，含所有 voucher_reasoning_record</li>
 *   <li>POST / → xiaowei 做账完写入；body 含 run record + 可选 voucherReasonings 数组</li>
 * </ul>
 *
 * <p>所有 JSON 字段（{@code failedSteps / configInitSummary / dataSources / appliedRules /
 * appliedPrompts / entries / sourceInvoices}）由 xiaowei 端定义内部结构，FN 透传不解析。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/bookkeeping-records")
@SaIgnore
public class OpenApiBookkeepingRecordsController {

    /** status 白名单 */
    static final String STATUS_SUCCESS = "success";
    static final String STATUS_PARTIAL_FAILED = "partial_failed";
    static final String STATUS_FAILED = "failed";
    static final Set<String> ALLOWED_STATUS = Set.of(STATUS_SUCCESS, STATUS_PARTIAL_FAILED, STATUS_FAILED);

    private final BookkeepingRunRecordRepository runRecordRepository;
    private final VoucherReasoningRecordRepository reasoningRecordRepository;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QAccountSets qAccountSets = QAccountSets.accountSets;

    // ═══════════════════════════════════════════════════════════════
    // GET /?accountSetsId=&period=  列出该期所有做账记录
    // ═══════════════════════════════════════════════════════════════

    @GetMapping
    public JsonResult list(@RequestParam Long accountSetsId,
                           @RequestParam String period) {
        if (accountSetsId == null) return JsonResult.failure("accountSetsId 不能为空");
        if (period == null || period.isBlank()) return JsonResult.failure("period 不能为空");
        if (!accountSetExists(accountSetsId)) {
            return JsonResult.failure("账套不存在：" + accountSetsId);
        }

        List<BookkeepingRunRecord> rows = runRecordRepository
                .findByAccountSetsIdAndPeriodOrderByRunAtDesc(accountSetsId, period);

        List<Map<String, Object>> data = new ArrayList<>(rows.size());
        for (BookkeepingRunRecord r : rows) data.add(toMap(r, null));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("accountSetsId", String.valueOf(accountSetsId));
        resp.put("period", period);
        resp.put("total", data.size());
        resp.put("records", data);
        return JsonResult.successful(resp);
    }

    // ═══════════════════════════════════════════════════════════════
    // GET /{runId}  完整详情含所有凭证推理
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/{runId}")
    public JsonResult detail(@PathVariable String runId) {
        if (runId == null || runId.isBlank()) return JsonResult.failure("runId 不能为空");
        Optional<BookkeepingRunRecord> opt = runRecordRepository.findByRunId(runId);
        if (opt.isEmpty()) return JsonResult.failure("做账记录不存在：runId=" + runId);

        List<VoucherReasoningRecord> reasonings = reasoningRecordRepository
                .findByRunIdOrderByCreatedAtAsc(runId);
        Map<String, Object> data = toMap(opt.get(), reasonings);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // POST  xiaowei 做账完写入
    //   body: { accountSetsId, period, runId, runAt, triggeredBy, status, summaryCn,
    //           totalVouchers, totalAmount, failedSteps, configInitSummary,
    //           voucherReasonings: [{ voucherKind, voucherTitle, producedBy, confidence,
    //                                 formulaCn, dataSources, reasoningText, appliedRules,
    //                                 appliedPrompts, entries, sourceInvoices, voucherId? }] }
    // ═══════════════════════════════════════════════════════════════

    @PostMapping
    @Transactional
    public JsonResult create(@RequestBody CreateRunRecordReq req) {
        // ── 入参校验 ─────────────────────────────────────
        String err = validateCreateReq(req);
        if (err != null) return JsonResult.failure(err);
        if (!accountSetExists(req.getAccountSetsId())) {
            return JsonResult.failure("账套不存在：" + req.getAccountSetsId());
        }
        // 防 run_id 冲突
        if (runRecordRepository.findByRunId(req.getRunId()).isPresent()) {
            return failureWithType("做账记录已存在：runId=" + req.getRunId(), "RUN_ID_DUPLICATE");
        }

        LocalDateTime now = LocalDateTime.now();

        // ── 写 BookkeepingRunRecord ───────────────────
        BookkeepingRunRecord run = new BookkeepingRunRecord();
        run.setAccountSetsId(req.getAccountSetsId());
        run.setPeriod(req.getPeriod());
        run.setRunId(req.getRunId());
        run.setRunAt(req.getRunAt() != null ? req.getRunAt() : now);
        run.setTriggeredBy(req.getTriggeredBy());
        run.setStatus(req.getStatus());
        run.setSummaryCn(req.getSummaryCn());
        run.setTotalVouchers(req.getTotalVouchers() != null ? req.getTotalVouchers() : 0);
        run.setTotalAmount(req.getTotalAmount());
        run.setFailedSteps(toJsonStringSafe(req.getFailedSteps()));
        run.setConfigInitSummary(toJsonStringSafe(req.getConfigInitSummary()));
        run.setCreatedAt(now);
        runRecordRepository.save(run);

        // ── 写 voucherReasonings ──────────────────────
        int reasoningsCreated = 0;
        List<Map<String, Object>> reasoningRows = new ArrayList<>();
        if (req.getVoucherReasonings() != null) {
            for (VoucherReasoningReq vr : req.getVoucherReasonings()) {
                VoucherReasoningRecord vrr = new VoucherReasoningRecord();
                vrr.setAccountSetsId(req.getAccountSetsId());
                vrr.setRunId(req.getRunId());
                vrr.setPeriod(req.getPeriod());
                vrr.setVoucherId(vr.getVoucherId());
                vrr.setVoucherKind(vr.getVoucherKind());
                vrr.setVoucherTitle(vr.getVoucherTitle());
                vrr.setProducedBy(vr.getProducedBy());
                vrr.setConfidence(vr.getConfidence());
                vrr.setFormulaCn(vr.getFormulaCn());
                vrr.setDataSources(toJsonStringSafe(vr.getDataSources()));
                vrr.setReasoningText(vr.getReasoningText());
                vrr.setAppliedRules(toJsonStringSafe(vr.getAppliedRules()));
                vrr.setAppliedPrompts(toJsonStringSafe(vr.getAppliedPrompts()));
                vrr.setEntries(toJsonStringSafe(vr.getEntries()));
                vrr.setSourceInvoices(toJsonStringSafe(vr.getSourceInvoices()));
                vrr.setCreatedAt(now);
                reasoningRecordRepository.save(vrr);
                reasoningsCreated++;
                reasoningRows.add(reasoningToMap(vrr));
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", req.getRunId());
        data.put("recordId", String.valueOf(run.getId()));
        data.put("voucherReasoningsCreated", reasoningsCreated);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════════

    String validateCreateReq(CreateRunRecordReq req) {
        if (req == null) return "请求体不能为空";
        if (req.getAccountSetsId() == null) return "accountSetsId 不能为空";
        if (req.getPeriod() == null || req.getPeriod().isBlank()) return "period 不能为空";
        if (!period_isValid(req.getPeriod())) return "period 格式应为 YYYY-MM：" + req.getPeriod();
        if (req.getRunId() == null || req.getRunId().isBlank()) return "runId 不能为空";
        if (req.getStatus() == null || req.getStatus().isBlank()) return "status 不能为空";
        if (!ALLOWED_STATUS.contains(req.getStatus())) {
            return "status 取值非法：" + req.getStatus() + "，允许：" + ALLOWED_STATUS;
        }
        return null;
    }

    static boolean period_isValid(String period) {
        // YYYY-MM 简单校验
        if (period.length() != 7) return false;
        if (period.charAt(4) != '-') return false;
        for (int i = 0; i < 4; i++) if (!Character.isDigit(period.charAt(i))) return false;
        for (int i = 5; i < 7; i++) if (!Character.isDigit(period.charAt(i))) return false;
        return true;
    }

    /** 序列化 JSON 字段；入参可能是 Object/Map/List/String，统一转 JSON 字符串 */
    static String toJsonStringSafe(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s.isBlank() ? null : s;
        return JSON.toJSONString(v);
    }

    /** 把 BookkeepingRunRecord 转响应 map；reasonings != null 时附加 voucherReasonings 数组（详情用） */
    Map<String, Object> toMap(BookkeepingRunRecord r, List<VoucherReasoningRecord> reasonings) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recordId", String.valueOf(r.getId()));
        m.put("accountSetsId", String.valueOf(r.getAccountSetsId()));
        m.put("period", r.getPeriod());
        m.put("runId", r.getRunId());
        m.put("runAt", r.getRunAt());
        m.put("triggeredBy", r.getTriggeredBy());
        m.put("status", r.getStatus());
        m.put("summaryCn", r.getSummaryCn());
        m.put("totalVouchers", r.getTotalVouchers());
        m.put("totalAmount", r.getTotalAmount());
        m.put("failedSteps", parseJsonSafe(r.getFailedSteps()));
        m.put("configInitSummary", parseJsonSafe(r.getConfigInitSummary()));
        m.put("createdAt", r.getCreatedAt());
        if (reasonings != null) {
            List<Map<String, Object>> reasoningList = new ArrayList<>(reasonings.size());
            for (VoucherReasoningRecord vr : reasonings) reasoningList.add(reasoningToMap(vr));
            m.put("voucherReasonings", reasoningList);
        }
        return m;
    }

    Map<String, Object> reasoningToMap(VoucherReasoningRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(r.getId()));
        m.put("runId", r.getRunId());
        m.put("accountSetsId", String.valueOf(r.getAccountSetsId()));
        m.put("period", r.getPeriod());
        m.put("voucherId", r.getVoucherId() != null ? String.valueOf(r.getVoucherId()) : null);
        m.put("voucherKind", r.getVoucherKind());
        m.put("voucherTitle", r.getVoucherTitle());
        m.put("producedBy", r.getProducedBy());
        m.put("confidence", r.getConfidence());
        m.put("formulaCn", r.getFormulaCn());
        m.put("dataSources", parseJsonSafe(r.getDataSources()));
        m.put("reasoningText", r.getReasoningText());
        m.put("appliedRules", parseJsonSafe(r.getAppliedRules()));
        m.put("appliedPrompts", parseJsonSafe(r.getAppliedPrompts()));
        m.put("entries", parseJsonSafe(r.getEntries()));
        m.put("sourceInvoices", parseJsonSafe(r.getSourceInvoices()));
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    /** JSON 字符串解析回对象；解析失败回退给原字符串 */
    Object parseJsonSafe(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return JSON.parse(s);
        } catch (Exception e) {
            log.warn("[FN-NEW-BOOKKEEPING-RUN-RECORD-PERSISTENCE] JSON 解析失败：{}", s);
            return s;
        }
    }

    static JsonResult failureWithType(String msg, String errorType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorType", errorType);
        return JsonResult.failure(msg).setData(data);
    }

    /** package-private test hook：单测覆写避开 BlazeJPAQuery 直查 */
    boolean accountSetExists(Long accountSetsId) {
        return bqf.selectFrom(qAccountSets).select(qAccountSets.id)
                .where(qAccountSets.id.eq(accountSetsId))
                .fetchFirst() != null;
    }

    // ═══════════════════════════════════════════════════════════════
    // DTO
    // ═══════════════════════════════════════════════════════════════

    @Data
    public static class CreateRunRecordReq {
        private Long accountSetsId;
        private String period;
        private String runId;
        private LocalDateTime runAt;
        private String triggeredBy;
        private String status;
        private String summaryCn;
        private Integer totalVouchers;
        private BigDecimal totalAmount;
        /** JSON 任意结构，FN 不解析 */
        private Object failedSteps;
        private Object configInitSummary;
        /** 可选：本次做账的所有凭证推理（含 entries / source_invoices 等） */
        private List<VoucherReasoningReq> voucherReasonings;
    }

    @Data
    public static class VoucherReasoningReq {
        private Long voucherId;
        private String voucherKind;
        private String voucherTitle;
        private String producedBy;
        private BigDecimal confidence;
        private String formulaCn;
        private Object dataSources;
        private String reasoningText;
        private Object appliedRules;
        private Object appliedPrompts;
        /** xiaowei 端可含 entries_used 子字段，FN 透传不解析 */
        private Object entries;
        private Object sourceInvoices;
    }
}
