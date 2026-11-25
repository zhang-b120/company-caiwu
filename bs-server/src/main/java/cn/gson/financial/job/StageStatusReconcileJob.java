package cn.gson.financial.job;

import cn.gson.financial.service.StageStatusSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * STAGE-STATUS-PERSIST §8 —— 阶段状态后台对账定时 job（替代旧"展示时回退派生"的容错）。
 *
 * <p>每天定时用派生重算与 task_period.step_* 比对，<b>漂移则告警</b>（{@link StageStatusSyncService#reconcile}
 * 内 {@code log.warn [STAGE-RECONCILE]}）。
 *
 * <p><b>默认仅告警不自动修复</b>（{@code autoFix=false}）——纯落库下自动改状态属高危，先告警留人核对；
 * 需要批量修复时由运维显式调 {@code POST /openapi/v1/task-period/reconcile {autoFix:true}}
 * 或临时把本 job 的 AUTO_FIX 置 true。对账覆盖<b>当前月 + 上一月</b>（跨月初仍能发现上月漂移）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StageStatusReconcileJob {

    /** 定时对账是否自动修复漂移（默认 false：仅告警，安全优先）。 */
    static final boolean AUTO_FIX = false;
    /** 单页大小（与 progress MAX_SIZE 对齐）。 */
    static final int PAGE_SIZE = 100;
    /** 安全上限：单次 job 最多翻多少页（防异常下死循环），100 页 × 100 = 1 万户/月。 */
    static final int MAX_PAGES = 200;

    private final StageStatusSyncService stageStatusSyncService;

    /** 每天 04:30 对账当前月 + 上一月。 */
    @Async
    @Scheduled(cron = "0 30 4 * * *")
    public void reconcileDaily() {
        YearMonth now = YearMonth.from(LocalDate.now());
        reconcilePeriod(now.toString());
        reconcilePeriod(now.minusMonths(1).toString());
    }

    /** 翻页对账某账期全量，汇总漂移数。 */
    void reconcilePeriod(String period) {
        int totalDrift = 0, totalFixed = 0, totalChecked = 0, page = 1;
        try {
            while (page <= MAX_PAGES) {
                StageStatusSyncService.ReconcileResult r =
                        stageStatusSyncService.reconcile(period, page, PAGE_SIZE, AUTO_FIX);
                totalDrift += r.driftCount;
                totalFixed += r.fixed;
                totalChecked += r.checked;
                if (!r.hasMore) break;
                page++;
            }
            if (totalDrift > 0) {
                log.warn("[STAGE-RECONCILE] period={} 对账完成：checked={} drift={} fixed={}（autoFix={}）",
                        period, totalChecked, totalDrift, totalFixed, AUTO_FIX);
            } else {
                log.info("[STAGE-RECONCILE] period={} 对账完成：checked={} 无漂移", period, totalChecked);
            }
        } catch (Exception e) {
            log.error("[STAGE-RECONCILE] period={} 对账 job 异常（page={}）", period, page, e);
        }
    }
}
