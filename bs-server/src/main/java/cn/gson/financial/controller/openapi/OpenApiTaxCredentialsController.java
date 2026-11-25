package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.TaxLoginAccount;
import cn.gson.financial.kernel.model.repo.AccountSetsRepository;
import cn.gson.financial.kernel.model.repo.TaxLoginAccountRepository;
import cn.gson.financial.security.SecurityConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FN-NEW-TAX-CREDENTIALS — 暴露税务局登录凭证 API。
 *
 * <p>GET /openapi/v1/account-sets/{id}/tax-credentials
 * <p>返回明文密码供 BT/RPA 自动登录电子税务局使用，单次拉取、不缓存。
 *
 * <p>错误码：
 * <ul>
 *   <li>4041 — 账套不存在</li>
 *   <li>4042 — 该账套未配置办税员</li>
 *   <li>4043 — 办税员已停用 (status='INACTIVE')</li>
 * </ul>
 *
 * <p><b>⚠️ 已废弃（FN-TAX-LOGIN-CREDENTIALS-WRITE-API，2026-06-05 统一登录信息落库）</b>：
 * 本端点读旧表 {@code tax_login_account}，与 {@code tax_officer} 分裂。登录信息已<b>统一走 {@code tax_officer}</b>：
 * 写 → {@code /openapi/v1/account-sets/{id}/tax-officers}（{@link OpenApiTaxOfficerWriteController}）、
 * 读 → {@code GET /openapi/v1/tax-officers?accountSetId=}（含 loginMode/loginType/proxyTaxNo 全字段）。
 * 本端点暂保留兼容存量 RPA 调用方，<b>新接入请勿再用</b>；待确认无消费方后下线。
 */
@Deprecated
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/account-sets")
@SaIgnore
public class OpenApiTaxCredentialsController {

    private final AccountSetsRepository accountSetsRepository;
    private final TaxLoginAccountRepository taxLoginAccountRepository;

    /** 本期固定 shandong 电子税务局。多税务局支持等 systemId 字段加完再扩。 */
    private static final String DEFAULT_PROVINCE = "shandong";

    /**
     * FN-SEC-T2（167）本端点返回<b>电税明文密码</b>，必须 X-Internal-Token 硬挡。
     * 复用 BT 内部 token（与 claim/result 同款，BT/RPA 已持有）；<b>配置为空时一律 403</b>（敏感端点不裸奔，140 口径）。
     */
    @Value("${financial.scan-direct.internal-token:}")
    String internalToken;

    @GetMapping("/{id}/tax-credentials")
    public JsonResult getTaxCredentials(@PathVariable Long id,
                                        HttpServletRequest request,
                                        HttpServletResponse response) {
        // 安全：拒绝缓存（CDN/浏览器/代理都不能存）
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");

        String caller = describeCaller(request);

        // FN-SEC-T2（167）敏感端点硬挡：缺/错 token → 403；token 未配置 → 同样 403（绝不裸奔暴露明文密码）。
        if (!internalTokenOk(request.getHeader(SecurityConstants.INTERNAL_TOKEN_HEADER))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            log.warn("[FN-SEC-T2] tax-credentials 拒绝：X-Internal-Token 缺失/不匹配/未配置 acc={} caller={}", id, caller);
            return JsonResult.failure("X-Internal-Token 缺失或不匹配", 403);
        }

        log.info("[FN-NEW-TAX-CREDENTIALS] GET tax-credentials acc={} caller={}", id, caller);

        if (id == null) return JsonResult.failure("id 不能为空", 4041);

        // 1. 账套必须存在 → 4041
        Optional<AccountSets> accOpt = accountSetsRepository.findById(id);
        if (accOpt.isEmpty()) {
            log.warn("[FN-NEW-TAX-CREDENTIALS] acc={} 不存在 caller={}", id, caller);
            return JsonResult.failure("账套不存在：" + id, 4041);
        }

        // 2. 找该账套的所有办税员记录（任意状态都拿；下一步再判 ACTIVE/INACTIVE）
        List<TaxLoginAccount> all = taxLoginAccountRepository.findByAccountSetsId(id);
        if (all.isEmpty()) {
            log.warn("[FN-NEW-TAX-CREDENTIALS] acc={} 未配置办税员 caller={}", id, caller);
            return JsonResult.failure("该账套未配置办税员，请先在 financial 系统添加", 4042);
        }

        // 3. 优先取 ACTIVE 的；如果只有 INACTIVE → 4043
        TaxLoginAccount active = all.stream()
                .filter(a -> "ACTIVE".equalsIgnoreCase(a.getStatus()))
                .findFirst()
                .orElse(null);
        if (active == null) {
            log.warn("[FN-NEW-TAX-CREDENTIALS] acc={} 办税员全部 INACTIVE caller={}", id, caller);
            return JsonResult.failure("办税员已停用 (status=INACTIVE)，请联系管理员激活", 4043);
        }

        // 4. 组装响应（password 走 decrypt() 单点 — 当前明文，未来加密只改一处）
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountSetsId", id);
        data.put("province", DEFAULT_PROVINCE);
        data.put("loginType", active.getLoginType());
        data.put("phoneNumber", active.getPhoneNumber());
        data.put("password", decryptPassword(active.getPassword()));
        data.put("status", active.getStatus());
        data.put("lastLoginAt", null);   // TODO Phase 2: 加 last_login_at 列后填
        data.put("expiresAt", null);     // TODO Phase 2: 密码到期机制

        log.info("[FN-NEW-TAX-CREDENTIALS] acc={} 凭证返回成功 phone={} caller={}",
                id, maskPhone(active.getPhoneNumber()), caller);
        return JsonResult.successful(data);
    }

    /**
     * 密码解密单点。
     * <p>当前 tax_login_account.password 为明文存储（RPA 直接消费）；
     * 未来若改为对称加密（如 AES-GCM），仅在本方法接 cipher.doFinal() 即可，
     * controller / DTO 无需变动。
     */
    String decryptPassword(String stored) {
        // 当前明文 — 见 TaxLoginAccount 实体注释 "密码（加密存储）" 仅为规划，实际未加密
        return stored;
    }

    /**
     * FN-SEC-T2（167）内部 token 校验：配置非空且 header 匹配才放行；<b>配置为空也拒</b>（敏感端点不裸奔）。
     * 与 {@code OpenApiInvoiceCollectController.internalTokenOk} 同口径。
     */
    boolean internalTokenOk(String token) {
        if (internalToken == null || internalToken.isBlank()) {
            log.error("[FN-SEC-T2] financial.scan-direct.internal-token 未配置，tax-credentials 一律 403");
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

    /** 手机号脱敏 — 仅打日志，13800138000 → 138****8000 */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
