package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.TaxOfficer;
import cn.gson.financial.kernel.model.repo.AccountSetsRepository;
import cn.gson.financial.kernel.model.repo.TaxOfficerRepository;
import cn.gson.financial.security.SecurityConstants;
import cn.gson.financial.task.workbench.service.TaskWorkbenchService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * FN-LOGIN-T1（181）— 暴露"已解析电税登录"接口，让 BT 开票登电子税务局与 financial 核验税务 RPA <b>同源</b>。
 *
 * <p>GET /openapi/v1/account-sets/{id}/etax-login
 *
 * <p>渠道一律以<b>账套级</b> {@code account_sets.login_type} 为准（空→{@code officer}、{@code password}→归一 {@code officer}），
 * 凭证从默认办税员按渠道取字段——内部直接复用
 * {@link TaskWorkbenchService#resolveEtaxLoginCredentials}（即核验税务 RPA 用的
 * {@code applyLoginCredentials} 同一套），<b>单一真源</b>，结构上不再与 BT 自读
 * {@code tax_officer.login_type} 分叉。
 *
 * <p>返回随 {@code loginType} 的凭证字段（与 {@code applyLoginCredentials} 逐字一致）：
 * <ul>
 *   <li>{@code officer}      → phone, password</li>
 *   <li>{@code proxy}        → proxyAccount(=phone), proxyPassword(=password), proxyTaxNo</li>
 *   <li>{@code legal_person} → legalName(=name), idCard, password</li>
 *   <li>{@code ca}           → 无明文字段（凭证由 OpenClaw 本地加载）</li>
 * </ul>
 *
 * <p>安全：{@code @SaIgnore} + X-Internal-Token 硬挡（与 {@code /tax-credentials} 同款，返明文密码 {@code no-store}）。
 *
 * <p>错误码：
 * <ul>
 *   <li>4041 — 账套不存在</li>
 *   <li>4042 — 该账套无默认办税员</li>
 *   <li>403  — X-Internal-Token 缺失 / 不匹配 / 未配置</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/account-sets")
@SaIgnore
public class OpenApiEtaxLoginController {

    private final AccountSetsRepository accountSetsRepository;
    private final TaxOfficerRepository taxOfficerRepository;
    private final TaskWorkbenchService taskWorkbenchService;

    /**
     * 本端点返回<b>电税明文密码</b>，必须 X-Internal-Token 硬挡。
     * 复用 BT 内部 token（与 {@code /tax-credentials} 同款）；<b>配置为空时一律 403</b>（敏感端点不裸奔）。
     */
    @Value("${financial.scan-direct.internal-token:}")
    String internalToken;

    @GetMapping("/{id}/etax-login")
    public JsonResult getEtaxLogin(@PathVariable Long id,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        // 安全：拒绝缓存（CDN/浏览器/代理都不能存明文密码）
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");

        String caller = describeCaller(request);

        // 敏感端点硬挡：缺/错 token → 403；token 未配置 → 同样 403（绝不裸奔暴露明文密码）。
        if (!internalTokenOk(request.getHeader(SecurityConstants.INTERNAL_TOKEN_HEADER))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            log.warn("[FN-LOGIN-T1] etax-login 拒绝：X-Internal-Token 缺失/不匹配/未配置 acc={} caller={}", id, caller);
            return JsonResult.failure("X-Internal-Token 缺失或不匹配", 403);
        }

        log.info("[FN-LOGIN-T1] GET etax-login acc={} caller={}", id, caller);

        if (id == null) return JsonResult.failure("id 不能为空", 4041);

        // 1. 账套必须存在 → 4041
        Optional<AccountSets> accOpt = accountSetsRepository.findById(id);
        if (accOpt.isEmpty()) {
            log.warn("[FN-LOGIN-T1] acc={} 不存在 caller={}", id, caller);
            return JsonResult.failure("账套不存在：" + id, 4041);
        }
        AccountSets as = accOpt.get();

        // 2. 默认办税员必须存在 → 4042（凭证从默认办税员按渠道取字段）
        TaxOfficer officer = taxOfficerRepository.findByAccountSetIdAndDefaultOfficerTrue(id).orElse(null);
        if (officer == null) {
            log.warn("[FN-LOGIN-T1] acc={} 无默认办税员 caller={}", id, caller);
            return JsonResult.failure("该账套无默认办税员，请先在 financial 任务工作台设置默认办税员", 4042);
        }

        // 3. 复用核验税务 RPA 同一套解析（applyLoginCredentials）产出 snake_case payload
        Map<String, Object> resolved = taskWorkbenchService.resolveEtaxLoginCredentials(as, officer);
        // login_type 已在 applyLoginCredentials 内归一（password→officer）
        String loginType = (String) resolved.get("login_type");
        if (loginType == null || loginType.isBlank()) loginType = "officer";

        // 4. 映射为 BT 消费的 camelCase 响应——凭证值一律读 applyLoginCredentials 产出，保证逐字同源
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountSetsId", id);
        data.put("province", as.getProvince());
        data.put("loginType", loginType);
        data.put("officerName", resolved.get("officer_name"));
        switch (loginType) {
            case "officer" -> {
                data.put("phone", resolved.get("phone"));
                data.put("password", resolved.get("password"));
            }
            case "proxy" -> {
                data.put("proxyAccount", resolved.get("proxy_account"));
                data.put("proxyPassword", resolved.get("proxy_password"));
                data.put("proxyTaxNo", resolved.get("proxy_tax_no"));
            }
            case "legal_person" -> {
                data.put("legalName", resolved.get("legal_name"));
                data.put("idCard", resolved.get("id_card"));
                data.put("password", resolved.get("password"));
            }
            case "ca" -> {
                // CA 证书：凭证由 OpenClaw 本地加载，无明文字段（与 applyLoginCredentials 一致）
            }
            default -> {
                // 未知类型兜底 phone/password，与 applyLoginCredentials default 分支一致
                data.put("phone", resolved.get("phone"));
                data.put("password", resolved.get("password"));
            }
        }
        // 账套统一社会信用代码（登录填税号用，= creditCode）
        data.put("taxNumber", as.getCreditCode());

        log.info("[FN-LOGIN-T1] acc={} etax-login 返回成功 loginType={} officer={} caller={}",
                id, loginType, resolved.get("officer_name"), caller);
        return JsonResult.successful(data);
    }

    /**
     * 内部 token 校验：配置非空且 header 匹配才放行；<b>配置为空也拒</b>（敏感端点不裸奔）。
     * 与 {@code OpenApiTaxCredentialsController.internalTokenOk} 同口径。
     */
    boolean internalTokenOk(String token) {
        if (internalToken == null || internalToken.isBlank()) {
            log.error("[FN-LOGIN-T1] financial.scan-direct.internal-token 未配置，etax-login 一律 403");
            return false;
        }
        return internalToken.equals(token);
    }

    /** 日志/审计用 — 不返客户端，仅打日志便于追溯调用方 */
    private static String describeCaller(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = req.getRemoteAddr();
        String ua = req.getHeader("User-Agent");
        return ip + " / " + (ua == null ? "?" : ua.substring(0, Math.min(40, ua.length())));
    }
}
