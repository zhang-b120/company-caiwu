package cn.gson.financial.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * FN-SEC-T1（167）安全审计 —— 独立 logger {@code SECURITY_AUDIT}（滚动文件，见 logback-spring.xml），
 * 为后续封禁 / 取证留数据。
 * <p>
 * 红线：<b>绝不记敏感明文</b>（密码 / 电税账号一律不入审计）；公司名、UA 记录便于追溯但做换行净化防日志注入。
 */
@Component
public class SecurityAuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("SECURITY_AUDIT");

    /**
     * lookup-by-credentials 成败审计。
     * @param result {@code SUCCESS} / {@code NOT_FOUND}
     * @param failCount 当前连续失败数（成功为 0）
     */
    public void lookup(String ip, String ua, String company, String result, long failCount) {
        AUDIT.info("lookup result={} ip={} failCount={} company=\"{}\" ua=\"{}\"",
                result, ip, failCount, clean(company), clean(ua));
    }

    /** lookup 因 IP 已锁定被拦（防撞库取证）。 */
    public void lookupLocked(String ip, String ua) {
        AUDIT.warn("lookup BLOCKED reason=locked ip={} ua=\"{}\"", ip, clean(ua));
    }

    /** invoice-collect 提交审计（IP + accountSetsId + orderNo + 结果）。 */
    public void collect(String ip, String ua, Long accountSetsId, String orderNo, String result) {
        AUDIT.info("collect result={} ip={} accountSetsId={} orderNo={} ua=\"{}\"",
                result, ip, accountSetsId, clean(orderNo), clean(ua));
    }

    /** 任意端点限流命中取证。 */
    public void rateLimited(String endpoint, String ip, String ua) {
        AUDIT.warn("rateLimited endpoint={} ip={} ua=\"{}\"", endpoint, ip, clean(ua));
    }

    /** 净化换行/引号，防日志注入伪造行。 */
    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("[\\r\\n\"]", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }
}
