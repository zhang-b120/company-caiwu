package cn.gson.financial.service;

import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FN-RPA-QUEUE-API + FN-QUEUE-FULLFLOW：服务器 RPA 无人值守轮询队列。
 * <p>{@code /rpa/pending-jobs} 派两类任务：
 * <ul>
 *   <li>{@code declare} —— 预申报已确认、国税申报未完成的账套（半自动：财务确认后 RPA 报税）；jobId={@code declare:flowPeriodId}。</li>
 *   <li>{@code full-flow} —— 自动做账+报税开关 ON（FN-AUTOPILOT-SWITCH）、当期未跑完的账套（全自动）；
 *       period=该户自己的 current_period；jobId={@code full-flow:flowRunId}。</li>
 * </ul>
 * flow_* 由 worker 经 flow-tools（{@code /openapi/v1/flow/*}）直接回写，本服务只<b>选派 + 认领去重 + 留痕</b>。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RpaQueueService {

    private final FlowPeriodRepository flowPeriodRepository;
    private final AccountSetsRepository accountSetsRepository;
    private final FlowDeclareItemRepository flowDeclareItemRepository;
    private final FlowRunRepository flowRunRepository;
    private final FlowService flowService;
    private final AutopilotService autopilotService;

    /** flow 阶段「已了结」状态（聚合判完成用）。 */
    private static final Set<String> DONE_STATES = Set.of("done", "skipped", "na");
    private static final String FULL_FLOW_STAGE = "full-flow";
    /** FN-CROSSPROC-CONFIRM：full-flow 认领 TTL（秒）——超时未回报视为进程崩/失联，自动释放重派，防卡 running。 */
    static final int FULL_FLOW_CLAIM_TTL_SECONDS = 1800;

    // ═══════════════════════ declare 队列（FN-RPA-QUEUE-API）═══════════════════════

    /** @see #pendingDeclareJobs(String, int, java.util.Set) */
    @Transactional
    public List<Map<String, Object>> pendingDeclareJobs(String period, int limit) {
        return pendingDeclareJobs(period, limit, Collections.emptySet());
    }

    /**
     * 派当期待报税任务并原子认领（st_declare_vat→running）。
     * @param excludeAccountIds 排除的账套（如自动驾驶账套走 full-flow，避免重复派 declare）
     */
    @Transactional
    public List<Map<String, Object>> pendingDeclareJobs(String period, int limit, Set<Long> excludeAccountIds) {
        if (limit <= 0) return List.of();
        // 多取一些以便过滤排除项后仍够 limit
        List<FlowPeriod> rows = flowPeriodRepository.findPendingDeclare(period, PageRequest.of(0, limit + 8));
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (FlowPeriod cand : rows) {
            if (jobs.size() >= limit) break;
            if (excludeAccountIds != null && excludeAccountIds.contains(cand.getAccountSetId())) continue;

            // FN-QUEUE-CLAIM-ATOMIC：行级写锁 + 锁内复检，防多 poller 并发重复派同户
            FlowPeriod fp = flowPeriodRepository.findByIdForUpdate(cand.getId()).orElse(null);
            if (fp == null) continue;
            String st = fp.getStDeclareVat();
            if ("running".equals(st) || done(st)) continue;   // 已被领 / 已完成

            AccountSets acc = accountSetsRepository.findById(fp.getAccountSetId()).orElse(null);
            jobs.add(declareJob("declare:" + fp.getId(), fp.getAccountSetId(), fp.getPeriod(),
                    acc != null ? acc.getProvince() : null, taxpayerTypeOf(acc), taxTypesOf(fp.getId())));

            fp.setStDeclareVat("running");   // 认领（锁内写，提交即对其他 poller 可见）
            flowPeriodRepository.save(fp);
        }
        if (!jobs.isEmpty()) log.info("[FN-RPA-QUEUE] 派发 {} 条 declare 任务 period={}", jobs.size(), period);
        return jobs;
    }

    // ═══════════════════════ full-flow 队列（FN-QUEUE-FULLFLOW）═══════════════════════

    /**
     * 派全自动账套「全流程」任务：自动驾驶 ON + 当期（该户自己的 current_period）未跑完 + 未在跑。
     * 认领去重：为命中户建一条 flow_run(stage='full-flow', status='running')，已有在跑的则跳过。
     */
    @Transactional
    public List<Map<String, Object>> pendingFullFlowJobs(int limit) {
        if (limit <= 0) return List.of();
        List<Long> accIds = autopilotService.autopilotAccountIds();
        if (accIds.isEmpty()) return List.of();

        List<Map<String, Object>> jobs = new ArrayList<>();
        for (AccountSets acc : accountSetsRepository.findAllById(accIds)) {
            if (jobs.size() >= limit) break;
            String period = toYearMonth(acc.getCurrentAccountDate());
            if (period == null) continue;   // 无当前账期 → 跳过

            // 保证主轴行存在（getOrCreatePeriod 幂等）
            FlowPeriod created = flowService.getOrCreatePeriod(acc.getId(), period);

            // FN-QUEUE-CLAIM-ATOMIC：行级写锁主轴行——并发 poller 串行化，锁内复检 + 插认领 run，
            // 与 declare 同口径（都锁 flow_period 行）；持锁者提交后，第二个 poller 才读到在跑 run → 跳过。
            FlowPeriod fp = flowPeriodRepository.findByIdForUpdate(created.getId()).orElse(created);
            if (flowComplete(fp)) continue;   // 当期已跑完 → 不派

            // FN-CROSSPROC-CONFIRM：认领 TTL 回滚——在跑的 full-flow run 若 started_at 超 TTL（进程崩/失联）
            // 视为陈旧、置 timeout 释放，不再卡 running；仍在 TTL 内的视为真在跑 → 跳过（防重复派）。
            LocalDateTime staleBefore = LocalDateTime.now().minusSeconds(FULL_FLOW_CLAIM_TTL_SECONDS);
            boolean liveClaim = false;
            for (FlowRun r : flowRunRepository.findByFlowPeriodIdAndStageAndStatus(fp.getId(), FULL_FLOW_STAGE, "running")) {
                if (r.getStartedAt() != null && r.getStartedAt().isAfter(staleBefore)) {
                    liveClaim = true;   // 仍在 TTL 内 → 真在跑
                } else {
                    r.setStatus("timeout");   // 超时陈旧认领 → 回滚释放
                    r.setEndedAt(LocalDateTime.now());
                    r.setErrorMsg("full-flow 认领超时(TTL " + FULL_FLOW_CLAIM_TTL_SECONDS + "s)未回报，自动释放重派");
                    flowRunRepository.save(r);
                    log.warn("[FN-RPA-QUEUE] full-flow 认领超时回滚 runId={} acc={} period={}", r.getId(), acc.getId(), period);
                }
            }
            if (liveClaim) continue;   // 有未超时的在跑认领 → 跳过

            FlowRun claim = new FlowRun();
            claim.setFlowPeriodId(fp.getId());
            claim.setAccountSetId(acc.getId());
            claim.setPeriod(period);
            claim.setStage(FULL_FLOW_STAGE);
            claim.setStatus("running");
            claim.setStartedAt(LocalDateTime.now());
            claim = flowRunRepository.save(claim);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("accountSetsId", acc.getId());
            params.put("period", period);
            params.put("province", acc.getProvince());
            params.put("taxpayerType", taxpayerTypeOf(acc));
            params.put("taxTypes", taxTypesOf(fp.getId()));
            params.put("autoDeclare", true);
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("jobId", "full-flow:" + claim.getId());
            job.put("type", "full-flow");
            job.put("params", params);
            jobs.add(job);
        }
        if (!jobs.isEmpty()) log.info("[FN-RPA-QUEUE] 派发 {} 条 full-flow 任务", jobs.size());
        return jobs;
    }

    /** 当前生效自动的账套 id（供 declare 队列排除，避免重复派）。 */
    public Set<Long> autopilotAccountIdSet() {
        return new HashSet<>(autopilotService.autopilotAccountIds());
    }

    // ═══════════════════════ 结果回报（留痕 + full-flow 认领收尾）═══════════════════════

    /**
     * 收 worker 结果。jobId 形如 {@code declare:123} / {@code full-flow:456}。
     * full-flow：把认领 run 置终态（done/failed + ended_at），使其不再「在跑」（失败可被重派重试）。
     * declare：仅留痕（flow_* 由 worker 回写）。
     */
    @Transactional
    public void recordJobResult(String jobId, String status, String error, Object ctx) {
        boolean ok = !("failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status));
        if (jobId != null && jobId.startsWith(FULL_FLOW_STAGE + ":")) {
            try {
                Long runId = Long.valueOf(jobId.substring((FULL_FLOW_STAGE + ":").length()));
                flowRunRepository.findById(runId).ifPresent(r -> {
                    r.setStatus(ok ? "done" : "failed");
                    r.setEndedAt(LocalDateTime.now());
                    if (error != null) r.setErrorMsg(error);
                    flowRunRepository.save(r);
                });
            } catch (NumberFormatException ignore) {
                // jobId 非法 → 仅留痕
            }
        }
        if (ok) {
            log.info("[FN-RPA-QUEUE] job 回报 jobId={} status={}", jobId, status);
        } else {
            log.error("[FN-RPA-QUEUE] ⚠️ job 失败告警 jobId={} status={} error={} ctx={}", jobId, status, error, ctx);
        }
    }

    // ═══════════════════════ 辅助 ═══════════════════════

    private static Map<String, Object> declareJob(String jobId, Long accId, String period, String province,
                                                  String taxpayerType, List<String> taxTypes) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("accountSetsId", accId);
        params.put("period", period);
        params.put("province", province);
        params.put("taxpayerType", taxpayerType);   // FN-QUEUE-ADD-TAXPAYERTYPE：worker 据此走对增值税入口
        params.put("taxTypes", taxTypes);
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("jobId", jobId);
        job.put("type", "declare");
        job.put("params", params);
        return job;
    }

    /** FN-QUEUE-ADD-TAXPAYERTYPE：账套 vat_type/个体 → 纳税人类型（GENERAL/SMALL_SCALE/INDIVIDUAL），口径同进度接口。 */
    private static String taxpayerTypeOf(AccountSets acc) {
        if (acc == null) return null;
        return AccountSetsProgressService.deriveTaxpayerType(
                acc.getVatType(), Boolean.TRUE.equals(acc.getIndividualBusiness()));
    }

    private List<String> taxTypesOf(Long flowPeriodId) {
        return flowDeclareItemRepository.findByFlowPeriodIdOrderByIdAsc(flowPeriodId).stream()
                .map(FlowDeclareItem::getCategory).filter(Objects::nonNull).distinct().collect(Collectors.toList());
    }

    /** 全 11 个 st_* 都已了结（done/skipped/na）→ 当期跑完。 */
    static boolean flowComplete(FlowPeriod fp) {
        return done(fp.getStLogin()) && done(fp.getStVerify()) && done(fp.getStInit())
                && done(fp.getStCollect()) && done(fp.getStBook()) && done(fp.getStCarry())
                && done(fp.getStPredeclare()) && done(fp.getStDeclareVat()) && done(fp.getStDeclareIit())
                && done(fp.getStPayment()) && done(fp.getStArchive());
    }

    private static boolean done(String s) {
        return s != null && DONE_STATES.contains(s);
    }

    /** 日期值归一为 yyyy-MM（java.sql.Date / LocalDate / 字符串 toString 前缀均 yyyy-MM-dd）。 */
    private static String toYearMonth(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.length() >= 7 ? s.substring(0, 7) : s;
    }
}
