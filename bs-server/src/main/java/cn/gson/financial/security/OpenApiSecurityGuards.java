package cn.gson.financial.security;

import cn.gson.financial.kernel.controller.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * FN-SEC-T1（167）控制器侧安全门面 —— 处理需要 body / 结果的安全动作：
 * <ul>
 *   <li>collect 按 accountSetsId 30/min（拦截器拿不到 body，放这里）；</li>
 *   <li>collect / lookup 的 {@code SECURITY_AUDIT} 审计（结果在控制器才知道）；</li>
 *   <li>lookup 连续失败 / 成功 喂给 {@link RateLimitService}（驱动拦截器的锁）。</li>
 * </ul>
 * <p>
 * IP / UA / 当前响应经 {@link RequestContextHolder} 取，<b>不改控制器方法签名</b>
 * （与 {@code WebMvcSupport} 既有用法一致），故纯 JUnit 单测（无 Spring 上下文、门面为 null）
 * 走原路径、不受影响。
 */
@Slf4j
@Component
public class OpenApiSecurityGuards {

    private final RateLimitService rateLimitService;
    private final RateLimitProperties props;
    private final ClientKeyResolver keyResolver;
    private final SecurityAuditLogger audit;

    public OpenApiSecurityGuards(RateLimitService rateLimitService, RateLimitProperties props,
                                 ClientKeyResolver keyResolver, SecurityAuditLogger audit) {
        this.rateLimitService = rateLimitService;
        this.props = props;
        this.keyResolver = keyResolver;
        this.audit = audit;
    }

    // ── collect ────────────────────────────────────────────────────────

    /** collect 按 accountSetsId 30/min；true=放行。null 账套或未启用 → 放行（IP 维度已在拦截器挡）。 */
    public boolean allowCollectByAccount(Long accountSetsId) {
        if (!props.isEnabled() || accountSetsId == null) {
            return true;
        }
        return rateLimitService.allow("rl:collect:acct:" + accountSetsId,
                props.getCollectAccountPerMin(), props.getWindowSeconds());
    }

    /** collect 审计：result ∈ {OK, REJECTED, RATE_LIMITED}。 */
    public void auditCollect(Long accountSetsId, String orderNo, String result) {
        HttpServletRequest req = currentRequest();
        audit.collect(clientIp(req), ua(req), accountSetsId, orderNo, result);
    }

    // ── lookup ─────────────────────────────────────────────────────────

    /**
     * lookup 结果回馈：成功 → 清零连续失败计数；失败 → 计数 +1（达阈值锁 IP）。同时审计。
     * @param company 仅审计用（公司名非机密；电税账号绝不传入）。
     */
    public void onLookupResult(boolean success, String company) {
        HttpServletRequest req = currentRequest();
        String ip = clientIp(req);
        if (success) {
            rateLimitService.clearLookupFailures(ip);
            audit.lookup(ip, ua(req), company, "SUCCESS", 0);
        } else {
            long n = rateLimitService.recordLookupFailure(ip,
                    props.getLookupFailLockThreshold(), props.getLookupLockMinutes());
            audit.lookup(ip, ua(req), company, "NOT_FOUND", n);
        }
    }

    // ── 429 ────────────────────────────────────────────────────────────

    /** 给当前响应置 429，返回统一文案 JsonResult（控制器侧超限走这条）。 */
    public JsonResult tooFrequent() {
        HttpServletResponse resp = currentResponse();
        if (resp != null) {
            resp.setStatus(SecurityConstants.TOO_MANY_REQUESTS);
        }
        return JsonResult.failure(SecurityConstants.TOO_FREQUENT_MSG)
                .setCode(SecurityConstants.TOO_MANY_REQUESTS);
    }

    // ── helpers ────────────────────────────────────────────────────────

    private String clientIp(HttpServletRequest req) {
        return req == null ? "unknown" : keyResolver.resolve(req);
    }

    private static String ua(HttpServletRequest req) {
        return req == null ? "" : req.getHeader("User-Agent");
    }

    private static HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs = currentAttrs();
        return attrs == null ? null : attrs.getRequest();
    }

    private static HttpServletResponse currentResponse() {
        ServletRequestAttributes attrs = currentAttrs();
        return attrs == null ? null : attrs.getResponse();
    }

    private static ServletRequestAttributes currentAttrs() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra) {
                return sra;
            }
        } catch (Exception ignore) {
            // 无请求上下文（如异步线程）→ 跳过，安全动作降级为不记录
        }
        return null;
    }
}
