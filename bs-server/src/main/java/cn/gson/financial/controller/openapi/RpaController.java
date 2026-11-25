package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.service.RpaQueueService;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FN-RPA-QUEUE-API：服务器 RPA 无人值守轮询端点（BT worker 依赖）。前缀 {@code /rpa}，@SaIgnore + Bearer token。
 * <ul>
 *   <li>{@code GET /rpa/pending-jobs?period=&limit=} —— 派当期「该报未报」账套报税任务（原子认领防重复派、限流 3-4 条）。</li>
 *   <li>{@code POST /rpa/job-result} —— 收 worker 结果留痕（flow_* 已由 worker 经 flow-tools 回写，此处只记日志+失败告警）。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/rpa")
@RequiredArgsConstructor
@SaIgnore
public class RpaController {

    private final RpaQueueService rpaQueueService;

    /** 与 RPA poller 约定的 Bearer token；未配置则放行（开发环境，与 oc.callback.token 同策略）。 */
    @Value("${rpa.poll.token:}")
    private String rpaToken;

    static final int DEFAULT_LIMIT = 4;
    static final int MAX_LIMIT = 10;

    @GetMapping("/pending-jobs")
    public JsonResult pendingJobs(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @RequestParam(required = false) String period,
                                  @RequestParam(required = false) Integer limit) {
        if (!checkToken(authorization)) return JsonResult.failure("Token 验证失败", 401);
        // period 缺省取当前自然月（YYYY-MM）；BT poller 也可显式指定申报所属期
        String p = StrUtil.isBlank(period) ? YearMonth.now().toString() : period.trim();
        int lim = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        try {
            // 全自动账套先派 full-flow（全流程）；剩余配额派 declare（半自动），并排除自动驾驶账套避免重复派
            List<Map<String, Object>> jobs = new java.util.ArrayList<>(rpaQueueService.pendingFullFlowJobs(lim));
            int remaining = lim - jobs.size();
            if (remaining > 0) {
                jobs.addAll(rpaQueueService.pendingDeclareJobs(p, remaining, rpaQueueService.autopilotAccountIdSet()));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("period", p);
            data.put("limit", lim);
            data.put("jobs", jobs);
            return JsonResult.successful(data);
        } catch (Exception e) {
            log.error("[FN-RPA-QUEUE] pending-jobs 失败 period={}", p, e);
            return JsonResult.failure("拉取待报任务失败：" + e.getMessage());
        }
    }

    @PostMapping("/job-result")
    public JsonResult jobResult(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @RequestBody Map<String, Object> body) {
        if (!checkToken(authorization)) return JsonResult.failure("Token 验证失败", 401);
        if (body == null || body.get("jobId") == null) return JsonResult.failure("jobId 不能为空");
        // jobId 形如 declare:123 / full-flow:456（typed）
        String jobId = body.get("jobId").toString();
        String status = (String) body.get("status");
        String error = body.get("error") != null ? body.get("error").toString() : null;
        try {
            rpaQueueService.recordJobResult(jobId, status, error, body.get("ctx"));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("jobId", jobId);
            data.put("acked", true);
            return JsonResult.successful(data);
        } catch (Exception e) {
            log.error("[FN-RPA-QUEUE] job-result 处理失败 jobId={}", jobId, e);
            return JsonResult.failure("回报处理失败：" + e.getMessage());
        }
    }

    private boolean checkToken(String authorization) {
        if (StrUtil.isBlank(rpaToken)) return true; // 未配置 token → 放行（开发环境）
        if (StrUtil.isBlank(authorization)) return false;
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        return rpaToken.equals(token);
    }
}
