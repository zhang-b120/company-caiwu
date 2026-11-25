package cn.gson.financial.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * FN-SEC-T1（167）限流 / 审计键解析 —— 转发层流量识别（168 后拓扑）。
 * <p>
 * 仅当请求来源 IP 属于可信转发层（{@link RateLimitProperties#getTrustedProxies()}，默认 8013 的 .112）时，
 * 才采信其注入的 {@code X-Forwarded-For} 第一段作为真实客户端键；其余来源照来源 IP，
 * <b>不信任非可信代理的 XFF</b>，防止攻击者伪造 XFF 绕过按 IP 的配额 / 锁定。
 */
@Component
public class ClientKeyResolver {

    private final RateLimitProperties props;

    public ClientKeyResolver(RateLimitProperties props) {
        this.props = props;
    }

    /**
     * 解析限流 / 审计用客户端键。
     * @return 真实客户端 IP（可信代理场景取 XFF 首段），来源 IP 不可得时返 {@code "unknown"}。
     */
    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr != null && props.getTrustedProxies().contains(remoteAddr)) {
            String first = firstXff(request.getHeader(SecurityConstants.XFF_HEADER));
            if (first != null) {
                return first;
            }
        }
        return remoteAddr == null || remoteAddr.isBlank() ? "unknown" : remoteAddr;
    }

    /** 取 {@code "client, proxy1, proxy2"} 形式 XFF 的第一段（真实客户端）；空则返 null。 */
    private static String firstXff(String xff) {
        if (xff == null || xff.isBlank()) {
            return null;
        }
        int comma = xff.indexOf(',');
        String first = (comma >= 0 ? xff.substring(0, comma) : xff).trim();
        return first.isEmpty() ? null : first;
    }
}
