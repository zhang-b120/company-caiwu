package cn.gson.financial.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * FN-SEC-T1（167）限流 / 防爆破 阈值配置（全部配置化进 yml，上线后据审计日志调参）。
 * <p>前缀 {@code financial.security.rate-limit}。阈值 ≤0 视为关闭该维度限流。
 *
 * <h3>转发层流量识别（168 后拓扑）</h3>
 * 商家页流量经 8013 转发而来，来源 IP 恒为 {@code 150.139.141.112}。仅当请求的来源 IP 在
 * {@link #trustedProxies} 内时，才采信其注入的 {@code X-Forwarded-For} 作为限流 / 审计键；
 * 其余来源（小程序直连等）一律照来源 IP，<b>不信任非可信代理的 XFF</b>（防伪造绕过配额）。
 */
@Component
@ConfigurationProperties(prefix = "financial.security.rate-limit")
public class RateLimitProperties {

    /** 总开关；false 时拦截器与账套维度限流整体放行（应急止血用）。 */
    private boolean enabled = true;

    /** 计数窗口（秒）。"每分钟"语义 = 60。 */
    private int windowSeconds = 60;

    /** 可信转发层来源 IP 集合：仅这些来源的 XFF 被采信。默认 8013 转发层 .112。 */
    private Set<String> trustedProxies = new LinkedHashSet<>(List.of("150.139.141.112"));

    /** POST /openapi/v1/invoice-collect 按 IP 每分钟上限。 */
    private int collectIpPerMin = 10;

    /** POST /openapi/v1/invoice-collect 按 accountSetsId 每分钟上限（控制器侧，需 body）。 */
    private int collectAccountPerMin = 30;

    /** POST /openapi/v1/account-sets/lookup-by-credentials 按 IP 每分钟上限。 */
    private int lookupIpPerMin = 5;

    /** lookup 连续失败次数达此值即锁该 IP（防撞库）。 */
    private int lookupFailLockThreshold = 10;

    /** lookup 锁定时长（分钟），到期自动解锁。 */
    private int lookupLockMinutes = 30;

    /** GET /openapi/v1/invoice-collect/{orderNo} 轮询 按 IP 每分钟上限。 */
    private int getOrderNoIpPerMin = 60;

    /** GET /openapi/v1/tax-classification（搜索 + 树）按 IP 每分钟上限。 */
    private int taxClassificationIpPerMin = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public Set<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(Set<String> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    public int getCollectIpPerMin() {
        return collectIpPerMin;
    }

    public void setCollectIpPerMin(int collectIpPerMin) {
        this.collectIpPerMin = collectIpPerMin;
    }

    public int getCollectAccountPerMin() {
        return collectAccountPerMin;
    }

    public void setCollectAccountPerMin(int collectAccountPerMin) {
        this.collectAccountPerMin = collectAccountPerMin;
    }

    public int getLookupIpPerMin() {
        return lookupIpPerMin;
    }

    public void setLookupIpPerMin(int lookupIpPerMin) {
        this.lookupIpPerMin = lookupIpPerMin;
    }

    public int getLookupFailLockThreshold() {
        return lookupFailLockThreshold;
    }

    public void setLookupFailLockThreshold(int lookupFailLockThreshold) {
        this.lookupFailLockThreshold = lookupFailLockThreshold;
    }

    public int getLookupLockMinutes() {
        return lookupLockMinutes;
    }

    public void setLookupLockMinutes(int lookupLockMinutes) {
        this.lookupLockMinutes = lookupLockMinutes;
    }

    public int getGetOrderNoIpPerMin() {
        return getOrderNoIpPerMin;
    }

    public void setGetOrderNoIpPerMin(int getOrderNoIpPerMin) {
        this.getOrderNoIpPerMin = getOrderNoIpPerMin;
    }

    public int getTaxClassificationIpPerMin() {
        return taxClassificationIpPerMin;
    }

    public void setTaxClassificationIpPerMin(int taxClassificationIpPerMin) {
        this.taxClassificationIpPerMin = taxClassificationIpPerMin;
    }
}
