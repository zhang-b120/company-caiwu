package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.LoanApplication;
import cn.gson.financial.service.LoanApplicationService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * 开放 API — 贷款评估 + 申请（FN-NEW-01，mock）
 * <p>
 * 给小程序改版的"金融服务"模块提供贷款评估、申请、查询。
 * 路径前缀 /openapi/v1/loan，全部接口免鉴权（@SaIgnore）。
 * <p>
 * 状态机：pending_evaluation → evaluated → submitted → under_review → approved / rejected
 * mock 阶段每 30 秒自动推进一档（lazy on-read，方便联调）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/loan")
@SaIgnore
public class OpenApiLoanController {

    private final LoanApplicationService loanService;

    // ═══════════════════════════════════════════════════════════════
    // POST /openapi/v1/loan/evaluate — 评估（不写表）
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/evaluate")
    public JsonResult evaluate(@RequestBody EvaluateReq req) {
        if (req == null) return JsonResult.failure("请求体不能为空");
        try {
            LoanApplicationService.EvaluationResult r =
                    loanService.evaluate(req.customerId, req.accountSetsId, req.loanType);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("loanType", r.loanType);
            data.put("evaluatedAmount", r.evaluatedAmount);
            Map<String, Object> rate = new LinkedHashMap<>();
            rate.put("low", r.interestRateLow);
            rate.put("high", r.interestRateHigh);
            data.put("interestRateRange", rate);
            data.put("recommendedProducts", r.recommendedProducts);
            data.put("evaluationBasis", r.evaluationBasis);
            return JsonResult.successful(data);
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("[FN-NEW-01] evaluate 失败", e);
            return JsonResult.failure("评估失败：" + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // POST /openapi/v1/loan/apply — 申请（写表）
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/apply")
    public JsonResult apply(@RequestBody ApplyReq req) {
        if (req == null) return JsonResult.failure("请求体不能为空");
        try {
            LoanApplication saved = loanService.apply(
                    req.customerId, req.accountSetsId, req.loanType,
                    req.applyAmount, req.purpose, req.materialsJson);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("applicationId", saved.getId());
            data.put("status", saved.getStatus());
            return JsonResult.successful(data);
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("[FN-NEW-01] apply 失败", e);
            return JsonResult.failure("申请失败：" + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // GET /openapi/v1/loan/applications?customerId= — 列表
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/applications")
    public JsonResult list(@RequestParam(required = false) Long customerId,
                           @RequestParam(required = false, defaultValue = "1") Integer page,
                           @RequestParam(required = false, defaultValue = "20") Integer size) {
        if (page == null || page < 1) page = 1;
        if (size == null || size < 1) size = 20;
        Page<LoanApplication> p = loanService.page(customerId, page, size);

        List<Map<String, Object>> rows = new ArrayList<>(p.getContent().size());
        for (LoanApplication a : p.getContent()) rows.add(toDto(a));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", rows);
        data.put("total", (int) p.getTotalElements());
        data.put("page", page);
        data.put("size", size);
        return JsonResult.successful(data);
    }

    @GetMapping("/applications/{id}")
    public JsonResult detail(@PathVariable Long id) {
        return loanService.findById(id)
                .map(a -> JsonResult.successful(toDto(a)))
                .orElseGet(() -> JsonResult.failure("贷款申请不存在：id=" + id));
    }

    // ═══════════════════════════════════════════════════════════════
    // DTOs
    // ═══════════════════════════════════════════════════════════════

    @Data
    public static class EvaluateReq {
        public Long customerId;
        public Long accountSetsId;
        public String loanType;
    }

    @Data
    public static class ApplyReq {
        public Long customerId;
        public Long accountSetsId;
        public String loanType;
        public BigDecimal applyAmount;
        public String purpose;
        public String materialsJson;
    }

    static Map<String, Object> toDto(LoanApplication a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("customerId", a.getCustomerId());
        m.put("accountSetsId", a.getAccountSetsId());
        m.put("loanType", a.getLoanType());
        m.put("applyAmount", a.getApplyAmount());
        m.put("evaluatedAmount", a.getEvaluatedAmount());
        m.put("interestRate", a.getInterestRate());
        m.put("termMonths", a.getTermMonths());
        m.put("purpose", a.getPurpose());
        m.put("status", a.getStatus());
        m.put("materialsJson", a.getMaterialsJson());
        m.put("rejectionReason", a.getRejectionReason());
        m.put("bankPartner", a.getBankPartner());
        m.put("contractNo", a.getContractNo());
        m.put("createdAt", a.getCreatedAt());
        m.put("updatedAt", a.getUpdatedAt());
        return m;
    }
}
