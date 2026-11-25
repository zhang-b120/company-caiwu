package cn.gson.financial.service;

import cn.gson.financial.task.workbench.service.TaskWorkbenchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * STAGE-STATUS-PERSIST §8（纯落库展示）—— 存量回填 + 后台对账修复。
 *
 * <p><b>单一派生口径</b>：两者都用 {@link AccountSetsProgressService#query}（派生）+
 * {@link AccountSetsProgressService#deriveStepColumns} 把派生映射到 task_period 的 step_* 列，
 * 经 {@link TaskWorkbenchService#backfillStepColumns} 落库——<b>不另写一套口径</b>。
 *
 * <ul>
 *   <li><b>回填</b>（必做前置）：把每个账套×账期的当前真实派生状态算一次写进 step_*，幂等可重跑、分批跑全量。</li>
 *   <li><b>对账</b>（建议）：派生重算 vs step_* 持久值比对，漂移则告警（log.warn）+ 可选自动修复。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StageStatusSyncService {

    private final AccountSetsProgressService progressService;
    private final TaskWorkbenchService taskWorkbenchService;

    // ─── 存量回填 ───────────────────────────────────────────────────

    /**
     * 回填一页：用派生算出 step_* 写进 task_period。幂等（同输入→同输出）。
     * @return 本页处理结果 + {@code hasMore}/{@code nextPage}（调用方据此翻页跑全量 6000 户）
     */
    public BatchResult backfill(String period, int page, int size, boolean dryRun) {
        AccountSetsProgressService.Result res = progressService.query(period, null, false, page, size);
        int processed = 0, updated = 0, failed = 0;
        for (Map<String, Object> rec : res.records) {
            Long accId = asLong(rec.get("accountSetsId"));
            Map<String, String> cols = AccountSetsProgressService.deriveStepColumns(rec);
            processed++;
            if (dryRun) continue;
            try {
                taskWorkbenchService.backfillStepColumns(accId, period, cols);
                updated++;
            } catch (Exception e) {
                failed++;
                log.error("[STAGE-BACKFILL] 回填失败 acc={} period={}", accId, period, e);
            }
        }
        BatchResult r = new BatchResult();
        r.period = period; r.page = page; r.size = res.size; r.total = res.total;
        r.processed = processed; r.updated = updated; r.failed = failed; r.dryRun = dryRun;
        r.hasMore = (long) page * res.size < res.total;
        r.nextPage = r.hasMore ? page + 1 : null;
        log.info("[STAGE-BACKFILL] period={} page={}/{} processed={} updated={} failed={} dryRun={}",
                period, page, (res.total + res.size - 1) / Math.max(res.size, 1), processed, updated, failed, dryRun);
        return r;
    }

    // ─── 后台对账（派生 vs 持久，漂移告警 + 可选修复） ─────────────────

    /**
     * 对账一页：派生重算与 stepsPersisted 比对，<b>仅对已有 task_period 行</b>报漂移（无行=尚未回填，不算漂移）。
     * @param autoFix true 则把漂移项按派生回填修复（替代旧"展示时回退派生"的容错）
     */
    public ReconcileResult reconcile(String period, int page, int size, boolean autoFix) {
        AccountSetsProgressService.Result res = progressService.query(period, null, false, page, size);
        List<Map<String, Object>> drifts = new ArrayList<>();
        int checked = 0, fixed = 0, missing = 0;
        for (Map<String, Object> rec : res.records) {
            Long accId = asLong(rec.get("accountSetsId"));
            @SuppressWarnings("unchecked")
            Map<String, Object> persisted = rec.get("stepsPersisted") instanceof Map
                    ? (Map<String, Object>) rec.get("stepsPersisted") : Collections.emptyMap();
            boolean hasTp = Boolean.TRUE.equals(persisted.get("hasTaskPeriod"));
            if (!hasTp) { missing++; continue; }   // 无 task_period 行 = 未回填，非漂移
            checked++;

            Map<String, String> derived = AccountSetsProgressService.deriveStepColumns(rec);
            Map<String, Object> diff = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : derived.entrySet()) {
                String pkey = AccountSetsProgressService.FIELD_TO_PERSISTED_KEY.get(e.getKey());
                Object pv = persisted.get(pkey);
                String pvs = pv == null ? null : String.valueOf(pv);
                if (!Objects.equals(e.getValue(), pvs)) {
                    diff.put(e.getKey(), Map.of("derived", String.valueOf(e.getValue()),
                            "persisted", String.valueOf(pvs)));
                }
            }
            if (diff.isEmpty()) continue;

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("accountSetsId", accId);
            d.put("period", period);
            d.put("diff", diff);
            drifts.add(d);
            log.warn("[STAGE-RECONCILE] 状态漂移 acc={} period={} diff={} autoFix={}", accId, period, diff, autoFix);
            if (autoFix) {
                try { taskWorkbenchService.backfillStepColumns(accId, period, derived); fixed++; }
                catch (Exception ex) { log.error("[STAGE-RECONCILE] 修复失败 acc={} period={}", accId, period, ex); }
            }
        }
        ReconcileResult r = new ReconcileResult();
        r.period = period; r.page = page; r.size = res.size; r.total = res.total;
        r.checked = checked; r.missing = missing; r.driftCount = drifts.size();
        r.fixed = fixed; r.autoFix = autoFix; r.drifts = drifts;
        r.hasMore = (long) page * res.size < res.total;
        r.nextPage = r.hasMore ? page + 1 : null;
        return r;
    }

    private static long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        return o == null ? 0L : Long.parseLong(o.toString());
    }

    public static class BatchResult {
        public String period;
        public int page, size, processed, updated, failed;
        public long total;
        public boolean dryRun, hasMore;
        public Integer nextPage;
    }

    public static class ReconcileResult {
        public String period;
        public int page, size, checked, missing, driftCount, fixed;
        public long total;
        public boolean autoFix, hasMore;
        public Integer nextPage;
        public List<Map<String, Object>> drifts;
    }
}
