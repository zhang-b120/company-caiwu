package cn.gson.financial.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * FN-SEC-T1（167）公网端点限流 + lookup 防爆破 拦截器（IP 维度）。
 * <p>
 * 作为 {@code HandlerInterceptor} bean 被 {@code WebMvcSupport.addInterceptors} 自动注册到 {@code /**}；
 * 本拦截器只管 {@code /openapi/} 前缀、其余请求秒放行。<b>轻量</b>：仅按 IP 计数，<b>不读 body</b>
 * （按 accountSetsId 的 30/min 在控制器侧做，见 {@link OpenApiSecurityGuards}）。
 *
 * <h3>限流键（168 后拓扑）</h3>
 * 经 {@link ClientKeyResolver} 解析：仅来源 IP=8013 转发层（.112）的请求采信其 XFF，其余照来源 IP。
 *
 * <h3>规则</h3>
 * <ul>
 *   <li>{@code POST /openapi/v1/invoice-collect} → IP 10/min</li>
 *   <li>{@code POST /openapi/v1/account-sets/lookup-by-credentials} → 先查锁（连错 10 次锁 30min）；再 IP 5/min</li>
 *   <li>{@code GET /openapi/v1/invoice-collect/{orderNo}} → IP 60/min（排除 /tasks）</li>
 *   <li>{@code GET /openapi/v1/tax-classification(/tree)} → IP 60/min</li>
 * </ul>
 * 超限统一 429 + 文案"操作过于频繁，请稍后再试"（不暴露阈值）。
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    static final String COLLECT_PATH = "/openapi/v1/invoice-collect";
    static final String LOOKUP_PATH = "/openapi/v1/account-sets/lookup-by-credentials";
    static final String TAX_CLASS_PREFIX = "/openapi/v1/tax-classification";

    private final RateLimitProperties props;
    private final RateLimitService rateLimitService;
    private final ClientKeyResolver keyResolver;
    private final SecurityAuditLogger audit;

    public RateLimitInterceptor(RateLimitProperties props, RateLimitService rateLimitService,
                                ClientKeyResolver keyResolver, SecurityAuditLogger audit) {
        this.props = props;
        this.rateLimitService = rateLimitService;
        this.keyResolver = keyResolver;
        this.audit = audit;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith("/openapi/") || !props.isEnabled()) {
            return true;
        }
        String method = request.getMethod();
        int window = props.getWindowSeconds();
        String clientKey = keyResolver.resolve(request);

        // 1) lookup-by-credentials：先查锁 → 再 IP 5/min
        if ("POST".equals(method) && LOOKUP_PATH.equals(uri)) {
            if (rateLimitService.isLookupLocked(clientKey)) {
                audit.lookupLocked(clientKey, request.getHeader("User-Agent"));
                return reject(request, response, "lookup", clientKey);
            }
            if (!rateLimitService.allow("rl:lookup:ip:" + clientKey, props.getLookupIpPerMin(), window)) {
                return reject(request, response, "lookup", clientKey);
            }
            return true;
        }

        // 2) invoice-collect 提交（精确路径，不含 /header /claim /result 等子路径）：IP 10/min
        if ("POST".equals(method) && COLLECT_PATH.equals(uri)) {
            if (!rateLimitService.allow("rl:collect:ip:" + clientKey, props.getCollectIpPerMin(), window)) {
                return reject(request, response, "collect", clientKey);
            }
            return true;
        }

        // 3) GET invoice-collect/{orderNo} 轮询：IP 60/min（排除 /tasks；/by-account/{id} 多段不匹配）
        if ("GET".equals(method) && isOrderNoGet(uri)) {
            if (!rateLimitService.allow("rl:get-order:ip:" + clientKey, props.getGetOrderNoIpPerMin(), window)) {
                return reject(request, response, "get-order", clientKey);
            }
            return true;
        }

        // 4) tax-classification 搜索 + 树：IP 60/min
        if ("GET".equals(method) && uri.startsWith(TAX_CLASS_PREFIX)) {
            if (!rateLimitService.allow("rl:taxclass:ip:" + clientKey, props.getTaxClassificationIpPerMin(), window)) {
                return reject(request, response, "tax-classification", clientKey);
            }
            return true;
        }

        return true;
    }

    /**
     * 是否为 {@code GET /openapi/v1/invoice-collect/{orderNo}} 单段轮询路径。
     * <p>仅匹配 {@code invoice-collect/<单段>}：排除字面量 {@code tasks}；
     * {@code by-account/{id}} 因有两段、{@code status-by-orders} 因是 POST 自然不进此分支。
     */
    static boolean isOrderNoGet(String uri) {
        String prefix = COLLECT_PATH + "/";
        if (!uri.startsWith(prefix)) {
            return false;
        }
        String rest = uri.substring(prefix.length());
        if (rest.isEmpty() || rest.indexOf('/') >= 0) {
            return false; // 多段（如 by-account/123）不算
        }
        return !"tasks".equals(rest);
    }

    /** 写 429 + 统一文案，记审计，返回 false 终止链路。 */
    private boolean reject(HttpServletRequest request, HttpServletResponse response,
                           String endpoint, String clientKey) throws IOException {
        audit.rateLimited(endpoint, clientKey, request.getHeader("User-Agent"));
        response.setStatus(SecurityConstants.TOO_MANY_REQUESTS);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(SecurityConstants.TOO_FREQUENT_JSON);
        return false;
    }
}
