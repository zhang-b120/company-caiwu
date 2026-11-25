package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.TaxCheckReport;
import cn.gson.financial.service.TaxCheckService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * 开放 API — 税务检查规则引擎（FN-NEW-02，mock 9 维度，Phase 1 实现 3 维度）。
 * <p>
 * 路径前缀 /openapi/v1/tax-check，全部接口免鉴权（@SaIgnore）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/tax-check")
@SaIgnore
public class OpenApiTaxCheckController {

    private final TaxCheckService taxCheckService;

    private static final ObjectMapper OM = new ObjectMapper().registerModule(new JavaTimeModule());

    // ═══════════════════════════════════════════════════════════════
    // POST /openapi/v1/tax-check/run
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/run")
    public JsonResult run(@RequestBody RunReq req) {
        if (req == null) return JsonResult.failure("请求体不能为空");
        try {
            TaxCheckReport report = taxCheckService.run(
                    req.customerId, req.accountSetsId, req.periodStart, req.periodEnd);
            return JsonResult.successful(toDto(report));
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("[FN-NEW-02] run 失败", e);
            return JsonResult.failure("税务检查失败：" + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // GET /openapi/v1/tax-check/reports?customerId=
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/reports")
    public JsonResult list(@RequestParam(required = false) Long customerId,
                           @RequestParam(required = false, defaultValue = "1") Integer page,
                           @RequestParam(required = false, defaultValue = "20") Integer size) {
        Page<TaxCheckReport> p = taxCheckService.page(customerId, page, size);
        List<Map<String, Object>> rows = new ArrayList<>(p.getContent().size());
        for (TaxCheckReport r : p.getContent()) rows.add(toDto(r));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", rows);
        data.put("total", (int) p.getTotalElements());
        data.put("page", page);
        data.put("size", size);
        return JsonResult.successful(data);
    }

    @GetMapping("/reports/{id}")
    public JsonResult detail(@PathVariable Long id) {
        TaxCheckReport r = taxCheckService.findById(id);
        return r == null
                ? JsonResult.failure("税务检查报告不存在：id=" + id)
                : JsonResult.successful(toDto(r));
    }

    // ═══════════════════════════════════════════════════════════════
    // DTOs
    // ═══════════════════════════════════════════════════════════════

    @Data
    public static class RunReq {
        public Long customerId;
        public Long accountSetsId;
        public LocalDate periodStart;
        public LocalDate periodEnd;
    }

    static Map<String, Object> toDto(TaxCheckReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reportId", r.getId());
        m.put("customerId", r.getCustomerId());
        m.put("accountSetsId", r.getAccountSetsId());
        m.put("periodStart", r.getPeriodStart());
        m.put("periodEnd", r.getPeriodEnd());
        m.put("overallScore", r.getOverallScore());
        m.put("riskLevel", r.getRiskLevel());
        m.put("dimensions", parseJsonList(r.getDimensionsJson()));
        m.put("findings", parseJsonList(r.getFindingsJson()));
        m.put("recommendations", parseJsonStrList(r.getRecommendationsJson()));
        m.put("reportPdfUrl", r.getReportPdfUrl());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> parseJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return OM.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("[FN-NEW-02] parse dimensions json failed", e);
            return List.of();
        }
    }

    static List<String> parseJsonStrList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return OM.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
