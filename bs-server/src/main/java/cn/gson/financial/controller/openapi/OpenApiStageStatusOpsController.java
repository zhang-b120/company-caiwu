package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.service.StageStatusSyncService;
import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;

/**
 * STAGE-STATUS-PERSIST §8 —— 阶段状态运维端点（存量回填 + 后台对账触发）。
 * 路径前缀 {@code /openapi/v1/task-period}。
 *
 * <ul>
 *   <li><b>POST /backfill</b> {@code {period, page?, size?, dryRun?}}：一次性回填（幂等可重跑）。
 *       用现有派生口径把账套×账期真实状态算一次写进 step_*。分页跑全量：循环至 {@code hasMore=false}。</li>
 *   <li><b>POST /reconcile</b> {@code {period, page?, size?, autoFix?}}：手动触发对账（与定时 job 同逻辑），
 *       派生 vs step_* 漂移则告警，{@code autoFix=true} 时按派生修复。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/task-period")
@SaIgnore
public class OpenApiStageStatusOpsController {

    static final int DEFAULT_SIZE = 100;

    private final StageStatusSyncService stageStatusSyncService;

    @PostMapping("/backfill")
    public JsonResult backfill(@RequestBody OpsReq req) {
        String period = normalizePeriod(req);
        if (period == null) return JsonResult.failure("period 不能为空或格式非法（yyyy-MM）");
        int page = req.getPage() == null || req.getPage() < 1 ? 1 : req.getPage();
        int size = req.getSize() == null || req.getSize() < 1 ? DEFAULT_SIZE : req.getSize();
        boolean dryRun = Boolean.TRUE.equals(req.getDryRun());
        try {
            return JsonResult.successful(stageStatusSyncService.backfill(period, page, size, dryRun));
        } catch (Exception e) {
            log.error("[STAGE-BACKFILL] 端点失败 period={} page={}", period, page, e);
            return JsonResult.failure("回填失败：" + e.getMessage());
        }
    }

    @PostMapping("/reconcile")
    public JsonResult reconcile(@RequestBody OpsReq req) {
        String period = normalizePeriod(req);
        if (period == null) return JsonResult.failure("period 不能为空或格式非法（yyyy-MM）");
        int page = req.getPage() == null || req.getPage() < 1 ? 1 : req.getPage();
        int size = req.getSize() == null || req.getSize() < 1 ? DEFAULT_SIZE : req.getSize();
        boolean autoFix = Boolean.TRUE.equals(req.getAutoFix());
        try {
            return JsonResult.successful(stageStatusSyncService.reconcile(period, page, size, autoFix));
        } catch (Exception e) {
            log.error("[STAGE-RECONCILE] 端点失败 period={} page={}", period, page, e);
            return JsonResult.failure("对账失败：" + e.getMessage());
        }
    }

    private String normalizePeriod(OpsReq req) {
        if (req == null || StrUtil.isBlank(req.getPeriod())) return null;
        try { return YearMonth.parse(req.getPeriod().trim()).toString(); }
        catch (Exception e) { return null; }
    }

    @Data
    public static class OpsReq {
        private String period;     // yyyy-MM
        private Integer page;      // 1-based，缺省 1
        private Integer size;      // 缺省 100（与 progress MAX_SIZE 对齐）
        private Boolean dryRun;    // backfill：true 只算不写
        private Boolean autoFix;   // reconcile：true 漂移按派生修复
    }
}
