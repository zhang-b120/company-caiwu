package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.service.FlowClaimService;
import cn.gson.financial.service.FlowClaimService.ClaimResult;
import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FN-FLOW-CLAIM：跨进程执行锁 / 登录锁端点。前缀 {@code /openapi/v1/flow}，@SaIgnore + 内部 token。
 * <ul>
 *   <li>{@code POST /flow/claim} {accountSetsId, period, stage, owner, ttlSeconds?} →
 *       {@code {ok, occupied, owner, claimedAt, expiresAt}}（ok=true 抢到；ok=false 占用中并返当前占用者）。</li>
 *   <li>{@code POST /flow/release} {accountSetsId, period, stage, owner} → {@code {released}}。</li>
 * </ul>
 * 两进程抢同 (account×period×stage) 只一个拿到锁；持有者崩溃后 TTL 到期可被接管。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/flow")
@SaIgnore
public class OpenApiFlowClaimController {

    private final FlowClaimService flowClaimService;

    @Value("${flow.upload.token:}")
    private String flowToken;

    @PostMapping("/claim")
    public JsonResult claim(@RequestHeader(value = "Authorization", required = false) String authorization,
                            @RequestBody ClaimReq req) {
        if (!checkToken(authorization)) return JsonResult.failure("Token 验证失败", 401);
        String err = validate(req);
        if (err != null) return JsonResult.failure(err);
        try {
            ClaimResult r = flowClaimService.claim(req.getAccountSetsId(), req.getPeriod().trim(),
                    req.getStage().trim(), req.effectiveOwner(), req.getTtlSeconds());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ok", r.isOk());
            data.put("claimed", r.isOk());            // FN-CROSSPROC-CONFIRM：ok 的别名（BT 读 claimed 更稳）
            data.put("occupied", !r.isOk());
            data.put("owner", r.getOwner());          // 占用中时=当前持有者
            data.put("claimedAt", r.getClaimedAt());
            data.put("expiresAt", r.getExpiresAt());
            return JsonResult.successful(data);
        } catch (Exception e) {
            log.error("[FN-FLOW-CLAIM] claim 失败 acc={} period={} stage={}",
                    req.getAccountSetsId(), req.getPeriod(), req.getStage(), e);
            return JsonResult.failure("抢锁失败：" + e.getMessage());
        }
    }

    @PostMapping("/release")
    public JsonResult release(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @RequestBody ClaimReq req) {
        if (!checkToken(authorization)) return JsonResult.failure("Token 验证失败", 401);
        String err = validate(req);
        if (err != null) return JsonResult.failure(err);
        try {
            boolean released = flowClaimService.release(req.getAccountSetsId(), req.getPeriod().trim(),
                    req.getStage().trim(), req.effectiveOwner());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("released", released);
            return JsonResult.successful(data);
        } catch (Exception e) {
            log.error("[FN-FLOW-CLAIM] release 失败 acc={} period={} stage={}",
                    req.getAccountSetsId(), req.getPeriod(), req.getStage(), e);
            return JsonResult.failure("释放失败：" + e.getMessage());
        }
    }

    private static String validate(ClaimReq req) {
        if (req == null || req.getAccountSetsId() == null) return "accountSetsId 不能为空";
        if (StrUtil.isBlank(req.getPeriod())) return "period 不能为空（格式 YYYY-MM）";
        if (StrUtil.isBlank(req.getStage())) return "stage 不能为空（执行步骤 或 办税员登录指纹）";
        return null;
    }

    private boolean checkToken(String authorization) {
        if (StrUtil.isBlank(flowToken)) return true;
        if (StrUtil.isBlank(authorization)) return false;
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        return flowToken.equals(token);
    }

    @Data
    public static class ClaimReq {
        private Long accountSetsId;
        private String period;
        private String stage;
        private String owner;
        private String operator;      // FN-CROSSPROC-CONFIRM：owner 的别名（BT 可发 operator）
        private Integer ttlSeconds;   // 可选，缺省 1800s

        /** owner 优先，其次 operator 别名。 */
        public String effectiveOwner() {
            return owner != null && !owner.isBlank() ? owner : operator;
        }
    }
}
