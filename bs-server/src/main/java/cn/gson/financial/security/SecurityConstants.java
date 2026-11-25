package cn.gson.financial.security;

/**
 * FN-SEC-T1（167）公网接口安全收紧 —— 共享常量。
 * <p>限流超限统一文案与 429 响应体（不暴露阈值，避免攻击者据此调参）。
 */
public final class SecurityConstants {

    private SecurityConstants() {
    }

    /** HTTP 429 Too Many Requests（{@code HttpServletResponse} 没有对应常量）。 */
    public static final int TOO_MANY_REQUESTS = 429;

    /** 限流 / 锁定 / 防爆破 命中后的统一对外文案（不暴露具体阈值）。 */
    public static final String TOO_FREQUENT_MSG = "操作过于频繁，请稍后再试";

    /**
     * 拦截器直写的 429 JSON 体，结构与 {@link cn.gson.financial.kernel.controller.JsonResult} 一致，
     * 让调用方无差别解析。固定字符串，无需序列化。
     */
    public static final String TOO_FREQUENT_JSON =
            "{\"success\":false,\"code\":429,\"msg\":\"" + TOO_FREQUENT_MSG + "\",\"data\":null}";

    /** 内部端点共享 token 头（claim/result/tax-credentials 等 BT 内部调用）。 */
    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    /** 转发层注入的真实客户端 IP 头。 */
    public static final String XFF_HEADER = "X-Forwarded-For";
}
