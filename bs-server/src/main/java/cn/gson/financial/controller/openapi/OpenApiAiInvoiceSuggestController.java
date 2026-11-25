package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.ai.InvoiceSuggestService;
import cn.gson.financial.ai.InvoiceSuggestService.BatchReq;
import cn.gson.financial.ai.InvoiceSuggestService.SuggestReq;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.repo.AccountSetsRepository;
import cn.gson.financial.security.SecurityConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * FN-AI-T2（185）— AI 智能填表端点 {@code POST /openapi/v1/ai/invoice-suggest}。
 *
 * <p>按 {@code accountSetsId} 取卖方上下文 + 用户文字 → 调 Qwen → 产出对齐 {@code CollectReq} 的建议草案
 * （编码走真 DB 码表两阶段解析、税号只提取不编造、税率按纳税人纠偏）。<b>不改 {@code /invoice-collect}</b>，
 * 用户确认后由 186 收敛回 CollectReq 提交开票。
 *
 * <p>安全：{@code @SaIgnore} + <b>X-Internal-Token 硬挡</b>（触发付费模型调用 + 含账套数据，
 * 与 invoice-files upload / etax-login 同口径；配置为空也 403）。
 *
 * <p>降级：Qwen key 空/超时/非 JSON → {@code {success:true, data:{suggestion:null, degraded:true}}}，
 * 不阻断开票（前端回退手填）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/ai")
@SaIgnore
public class OpenApiAiInvoiceSuggestController {

    private final InvoiceSuggestService invoiceSuggestService;
    private final AccountSetsRepository accountSetsRepository;

    /** 与 claim/result/etax-login 同款内部 token；配置为空一律 403。 */
    @Value("${financial.scan-direct.internal-token:}")
    String internalToken;

    @PostMapping("/invoice-suggest")
    public JsonResult invoiceSuggest(@RequestBody(required = false) SuggestReq req,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
        // 敏感端点硬挡：缺/错/未配置 token → 403
        if (!internalTokenOk(request.getHeader(SecurityConstants.INTERNAL_TOKEN_HEADER))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            log.warn("[FN-AI-T2] invoice-suggest 拒绝：X-Internal-Token 缺失/不匹配/未配置");
            return JsonResult.failure("X-Internal-Token 缺失或不匹配", 403);
        }

        if (req == null || req.getAccountSetsId() == null || req.getAccountSetsId().isBlank()) {
            return JsonResult.failure("accountSetsId 必填").setData(Map.of("errorType", "VALIDATION"));
        }
        Long accountSetsId;
        try {
            accountSetsId = Long.valueOf(req.getAccountSetsId().trim());
        } catch (NumberFormatException e) {
            return JsonResult.failure("accountSetsId 非法").setData(Map.of("errorType", "VALIDATION"));
        }

        Optional<AccountSets> accOpt = accountSetsRepository.findById(accountSetsId);
        if (accOpt.isEmpty()) {
            return JsonResult.failure("账套不存在：" + accountSetsId, 4041);
        }

        Map<String, Object> data = invoiceSuggestService.suggest(accOpt.get(), req);
        return JsonResult.successful(data);
    }

    /**
     * FN-AI-T4（188）批量 / 一对多：一个卖方账套 → 多张票。入参同 invoice-suggest + 可选 commonItems。
     * 返 {@code {suggestions[], count, degraded, warnings, accountSets}}；每张逐字复用单张后处理。
     * <p>同款 X-Internal-Token 硬挡；<b>不改单张端点</b>。
     */
    @PostMapping("/invoice-suggest-batch")
    public JsonResult invoiceSuggestBatch(@RequestBody(required = false) BatchReq req,
                                          HttpServletRequest request,
                                          HttpServletResponse response) {
        if (!internalTokenOk(request.getHeader(SecurityConstants.INTERNAL_TOKEN_HEADER))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            log.warn("[FN-AI-T4] invoice-suggest-batch 拒绝：X-Internal-Token 缺失/不匹配/未配置");
            return JsonResult.failure("X-Internal-Token 缺失或不匹配", 403);
        }
        if (req == null || req.getAccountSetsId() == null || req.getAccountSetsId().isBlank()) {
            return JsonResult.failure("accountSetsId 必填").setData(Map.of("errorType", "VALIDATION"));
        }
        Long accountSetsId;
        try {
            accountSetsId = Long.valueOf(req.getAccountSetsId().trim());
        } catch (NumberFormatException e) {
            return JsonResult.failure("accountSetsId 非法").setData(Map.of("errorType", "VALIDATION"));
        }
        Optional<AccountSets> accOpt = accountSetsRepository.findById(accountSetsId);
        if (accOpt.isEmpty()) {
            return JsonResult.failure("账套不存在：" + accountSetsId, 4041);
        }
        Map<String, Object> data = invoiceSuggestService.suggestBatch(accOpt.get(), req);
        return JsonResult.successful(data);
    }

    boolean internalTokenOk(String token) {
        if (internalToken == null || internalToken.isBlank()) {
            log.error("[FN-AI-T2] financial.scan-direct.internal-token 未配置，invoice-suggest 一律 403");
            return false;
        }
        return internalToken.equals(token);
    }
}
