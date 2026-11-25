package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.service.LoginLockService;
import cn.gson.financial.service.LoginLockService.AcquireResult;
import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FN-LOGIN-LOCK-BLOCKING：办税员登录锁端点（阻塞抢锁 + 心跳续期）。前缀 {@code /openapi/v1/flow}，@SaIgnore + 内部 token。
 * <ul>
 *   <li>{@code POST /flow/login-lock/acquire} {lockKey, owner, ttlSeconds?, waitMs?} → 拿不到时 hold 到可用或超时；
 *       返 {@code {acquired, timeout, owner, claimedAt, expiresAt}}（acquired=true 拿到；timeout=true 等待超时并返当前持有者）。</li>
 *   <li>{@code POST /flow/login-lock/heartbeat} {lockKey, owner, ttlSeconds?} → 续期，返 {@code {renewed, expiresAt}}（丢锁 renewed=false）。</li>
 *   <li>{@code POST /flow/login-lock/release} {lockKey, owner} → {@code {released}}。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/flow")
@SaIgnore
public class OpenApiLoginLockController {

    private final LoginLockService loginLockService;

    @Value("${flow.upload.token:}")
    private String flowToken;

    @PostMapping("/login-lock/acquire")
    public JsonResult acquire(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @RequestBody LockReq req) {
        if (!checkToken(authorization)) return JsonResult.failure("Token 验证失败", 401);
        if (req == null || StrUtil.isBlank(req.getLockKey())) return JsonResult.failure("lockKey 不能为空");
        int ttl = ttl(req.effectiveTtlSeconds());
        long waitMs = req.getWaitMs() != null ? req.getWaitMs() : 0L;
        try {
            AcquireResult r = loginLockService.acquire(req.getLockKey().trim(), req.effectiveOwner(), ttl, waitMs);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("acquired", r.isAcquired());
            data.put("timeout", r.isTimeout());
            data.put("owner", r.getOwner());        // timeout 时=当前持有者
            data.put("claimedAt", r.getClaimedAt());
            data.put("expiresAt", r.getExpiresAt());
            return JsonResult.successful(data);
        } catch (Exception e) {
            log.error("[FN-LOGIN-LOCK] acquire 失败 lockKey={}", req.getLockKey(), e);
            return JsonResult.failure("抢登录锁失败：" + e.getMessage());
        }
    }

    /** FN-CROSSPROC-CONFIRM：/renew 为 /heartbeat 的别名（BT 可用 renew），同逻辑。 */
    @PostMapping({"/login-lock/heartbeat", "/login-lock/renew"})
    public JsonResult heartbeat(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @RequestBody LockReq req) {
        if (!checkToken(authorization)) return JsonResult.failure("Token 验证失败", 401);
        if (req == null || StrUtil.isBlank(req.getLockKey())) return JsonResult.failure("lockKey 不能为空");
        String owner = req.effectiveOwner();
        if (StrUtil.isBlank(owner)) return JsonResult.failure("owner 不能为空");
        try {
            LocalDateTime exp = loginLockService.heartbeat(req.getLockKey().trim(), owner, ttl(req.effectiveTtlSeconds()));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("renewed", exp != null);   // false=已丢锁（被接管/释放）
            data.put("expiresAt", exp);
            return JsonResult.successful(data);
        } catch (Exception e) {
            log.error("[FN-LOGIN-LOCK] heartbeat 失败 lockKey={}", req.getLockKey(), e);
            return JsonResult.failure("续期失败：" + e.getMessage());
        }
    }

    @PostMapping("/login-lock/release")
    public JsonResult release(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @RequestBody LockReq req) {
        if (!checkToken(authorization)) return JsonResult.failure("Token 验证失败", 401);
        if (req == null || StrUtil.isBlank(req.getLockKey()) || StrUtil.isBlank(req.effectiveOwner())) {
            return JsonResult.failure("lockKey / owner 不能为空");
        }
        try {
            boolean released = loginLockService.release(req.getLockKey().trim(), req.effectiveOwner());
            return JsonResult.successful(Map.of("released", released));
        } catch (Exception e) {
            log.error("[FN-LOGIN-LOCK] release 失败 lockKey={}", req.getLockKey(), e);
            return JsonResult.failure("释放失败：" + e.getMessage());
        }
    }

    private static int ttl(Integer ttlSeconds) {
        return ttlSeconds != null && ttlSeconds > 0 ? ttlSeconds : LoginLockService.DEFAULT_TTL_SECONDS;
    }

    private boolean checkToken(String authorization) {
        if (StrUtil.isBlank(flowToken)) return true;
        if (StrUtil.isBlank(authorization)) return false;
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        return flowToken.equals(token);
    }

    @Data
    public static class LockReq {
        private String lockKey;
        private String owner;
        private String operator;      // FN-CROSSPROC-CONFIRM：owner 别名（BT 可发 operator）
        private Integer ttlSeconds;   // 缺省 600s
        private Long ttlMs;           // FN-CROSSPROC-CONFIRM：ttlSeconds 的毫秒别名（ttlSeconds 缺省时用，向上取整秒）
        private Long waitMs;          // acquire 阻塞上限；缺省 0=非阻塞

        /** owner 优先，其次 operator 别名。 */
        public String effectiveOwner() {
            return owner != null && !owner.isBlank() ? owner : operator;
        }

        /** ttlSeconds 优先；否则 ttlMs 换算秒（向上取整）；都无返 null（走默认）。 */
        public Integer effectiveTtlSeconds() {
            if (ttlSeconds != null) return ttlSeconds;
            if (ttlMs != null && ttlMs > 0) return (int) Math.max(1, (ttlMs + 999) / 1000);
            return null;
        }
    }
}
