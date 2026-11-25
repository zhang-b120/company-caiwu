package cn.gson.financial.service;

import cn.gson.financial.controller.openapi.OpenApiFlowController.*;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * FN-FLOW-02：flow 新模型 CRUD + 回写（幂等 upsert，键即各表 UNIQUE）。
 * <p>聚合（st_* 最差值）与预申报 gate 在 FN-FLOW-03 增量补充——本类先提供建行/回写/查询。
 * <p><b>彻底独立</b>：不触碰 / 不同步 {@code task_period}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class FlowService {

    private final FlowPeriodRepository periodRepo;
    private final FlowDeclareItemRepository declareRepo;
    private final FlowPaymentItemRepository paymentRepo;
    private final FlowCollectItemRepository collectRepo;
    private final FlowRunRepository runRepo;

    // ═══════════════════════ flow_period ═══════════════════════

    /** 按 (account_set_id, period) get-or-create 主轴行（幂等，并发撞 UNIQUE 时回查）。 */
    @Transactional
    public FlowPeriod getOrCreatePeriod(Long accountSetId, String period) {
        return periodRepo.findByAccountSetIdAndPeriod(accountSetId, period)
                .orElseGet(() -> {
                    try {
                        FlowPeriod p = new FlowPeriod();
                        p.setAccountSetId(accountSetId);
                        p.setPeriod(period);
                        return periodRepo.saveAndFlush(p);
                    } catch (DataIntegrityViolationException dup) {
                        // 并发下另一个事务先建了 → 回查
                        return periodRepo.findByAccountSetIdAndPeriod(accountSetId, period)
                                .orElseThrow(() -> dup);
                    }
                });
    }

    /** upsert 主轴行的元信息（checks_json / current_stage / error_*）；st_* 由聚合驱动，本接口不直改。 */
    @Transactional
    public FlowPeriod upsertPeriod(PeriodUpsertReq req) {
        FlowPeriod p = getOrCreatePeriod(req.getAccountSetsId(), req.getPeriod());
        if (req.getChecksJson() != null) p.setChecksJson(req.getChecksJson());
        if (req.getCurrentStage() != null) p.setCurrentStage(req.getCurrentStage());
        if (req.getErrorStage() != null) p.setErrorStage(req.getErrorStage());
        if (req.getErrorMsg() != null) p.setErrorMsg(req.getErrorMsg());
        return periodRepo.save(p);
    }

    public FlowPeriod findPeriod(Long accountSetId, String period) {
        return periodRepo.findByAccountSetIdAndPeriod(accountSetId, period).orElse(null);
    }

    public List<FlowDeclareItem> listDeclareItems(Long flowPeriodId) {
        return declareRepo.findByFlowPeriodIdOrderByIdAsc(flowPeriodId);
    }

    public List<FlowPaymentItem> listPaymentItems(Long flowPeriodId) {
        return paymentRepo.findByFlowPeriodIdOrderByIdAsc(flowPeriodId);
    }

    public List<FlowCollectItem> listCollectItems(Long flowPeriodId) {
        return collectRepo.findByFlowPeriodIdOrderByIdAsc(flowPeriodId);
    }

    public List<FlowRun> listRuns(Long flowPeriodId, String stage) {
        return stage == null || stage.isBlank()
                ? runRepo.findByFlowPeriodIdOrderByIdDesc(flowPeriodId)
                : runRepo.findByFlowPeriodIdAndStageOrderByIdDesc(flowPeriodId, stage);
    }

    // ═══════════════════════ flow_declare_item ═══════════════════════

    /** upsert 申报明细（幂等键 flow_period_id+category+tax_name）。null 字段不覆盖（部分回写）。 */
    @Transactional
    public FlowDeclareItem upsertDeclareItem(DeclareItemReq req) {
        FlowPeriod fp = getOrCreatePeriod(req.getAccountSetsId(), req.getPeriod());
        FlowDeclareItem it = declareRepo
                .findByFlowPeriodIdAndCategoryAndTaxName(fp.getId(), req.getCategory(), req.getTaxName())
                .orElseGet(() -> {
                    FlowDeclareItem n = new FlowDeclareItem();
                    n.setFlowPeriodId(fp.getId());
                    n.setAccountSetId(fp.getAccountSetId());
                    n.setPeriod(fp.getPeriod());
                    n.setCategory(req.getCategory());
                    n.setTaxName(req.getTaxName());
                    return n;
                });
        if (req.getDeclarePeriod() != null) it.setDeclarePeriod(req.getDeclarePeriod());
        if (req.getDeclareDeadline() != null) it.setDeclareDeadline(req.getDeclareDeadline());
        if (req.getDryRun() != null) it.setDryRun(req.getDryRun());
        if (req.getStatus() != null) it.setStatus(req.getStatus());
        if (req.getDeclaredAmount() != null) it.setDeclaredAmount(req.getDeclaredAmount());
        if (req.getReceiptNo() != null) it.setReceiptNo(req.getReceiptNo());
        if (req.getResultJson() != null) it.setResultJson(req.getResultJson());
        if (req.getScreenshotUrls() != null) it.setScreenshotUrls(req.getScreenshotUrls());
        if (req.getErrorMsg() != null) it.setErrorMsg(req.getErrorMsg());
        if (req.getLastRunId() != null) it.setLastRunId(req.getLastRunId());
        if (req.getExecutedAt() != null) it.setExecutedAt(req.getExecutedAt());
        FlowDeclareItem saved = declareRepo.save(it);
        recomputeStages(fp);
        return saved;
    }

    // ═══════════════════════ flow_payment_item ═══════════════════════

    /** upsert 缴款明细（幂等键 flow_period_id+tax_category+tax_name）。 */
    @Transactional
    public FlowPaymentItem upsertPaymentItem(PaymentItemReq req) {
        FlowPeriod fp = getOrCreatePeriod(req.getAccountSetsId(), req.getPeriod());
        FlowPaymentItem it = paymentRepo
                .findByFlowPeriodIdAndTaxCategoryAndTaxName(fp.getId(), req.getTaxCategory(), req.getTaxName())
                .orElseGet(() -> {
                    FlowPaymentItem n = new FlowPaymentItem();
                    n.setFlowPeriodId(fp.getId());
                    n.setAccountSetId(fp.getAccountSetId());
                    n.setPeriod(fp.getPeriod());
                    n.setTaxCategory(req.getTaxCategory());
                    n.setTaxName(req.getTaxName());
                    return n;
                });
        if (req.getTaxAmount() != null) it.setTaxAmount(req.getTaxAmount());
        if (req.getPaidAmount() != null) it.setPaidAmount(req.getPaidAmount());
        if (req.getUnpaidAmount() != null) it.setUnpaidAmount(req.getUnpaidAmount());
        if (req.getPayStatus() != null) it.setPayStatus(req.getPayStatus());
        if (req.getPayDeadline() != null) it.setPayDeadline(req.getPayDeadline());
        if (req.getReceiptNo() != null) it.setReceiptNo(req.getReceiptNo());
        if (req.getScreenshotUrls() != null) it.setScreenshotUrls(req.getScreenshotUrls());
        if (req.getLastRunId() != null) it.setLastRunId(req.getLastRunId());
        if (req.getQueriedAt() != null) it.setQueriedAt(req.getQueriedAt());
        FlowPaymentItem saved = paymentRepo.save(it);
        recomputeStages(fp);
        return saved;
    }

    // ═══════════════════════ flow_collect_item ═══════════════════════

    /** upsert 采集明细（幂等键 flow_period_id+source）。 */
    @Transactional
    public FlowCollectItem upsertCollectItem(CollectItemReq req) {
        FlowPeriod fp = getOrCreatePeriod(req.getAccountSetsId(), req.getPeriod());
        FlowCollectItem it = collectRepo
                .findByFlowPeriodIdAndSource(fp.getId(), req.getSource())
                .orElseGet(() -> {
                    FlowCollectItem n = new FlowCollectItem();
                    n.setFlowPeriodId(fp.getId());
                    n.setAccountSetId(fp.getAccountSetId());
                    n.setPeriod(fp.getPeriod());
                    n.setSource(req.getSource());
                    return n;
                });
        if (req.getStatus() != null) it.setStatus(req.getStatus());
        if (req.getItemCount() != null) it.setItemCount(req.getItemCount());
        if (req.getResultJson() != null) it.setResultJson(req.getResultJson());
        if (req.getScreenshotUrls() != null) it.setScreenshotUrls(req.getScreenshotUrls());
        if (req.getErrorMsg() != null) it.setErrorMsg(req.getErrorMsg());
        if (req.getLastRunId() != null) it.setLastRunId(req.getLastRunId());
        if (req.getExecutedAt() != null) it.setExecutedAt(req.getExecutedAt());
        FlowCollectItem saved = collectRepo.save(it);
        recomputeStages(fp);
        return saved;
    }

    // ═══════════════════════ flow_run ═══════════════════════

    /** 开跑：插一条 running 流水（started_at=now），返回 run（含 id）。 */
    @Transactional
    public FlowRun startRun(RunStartReq req) {
        FlowPeriod fp = getOrCreatePeriod(req.getAccountSetsId(), req.getPeriod());
        FlowRun r = new FlowRun();
        r.setFlowPeriodId(fp.getId());
        r.setAccountSetId(fp.getAccountSetId());
        r.setPeriod(fp.getPeriod());
        r.setStage(req.getStage());
        r.setRefType(req.getRefType());
        r.setRefId(req.getRefId());
        if (req.getAttemptNo() != null) r.setAttemptNo(req.getAttemptNo());
        if (req.getDryRun() != null) r.setDryRun(req.getDryRun());
        r.setStatus("running");
        r.setStartedAt(LocalDateTime.now());
        FlowRun saved = runRepo.save(r);
        recomputeStages(fp);   // 开跑即把无明细阶段 st_* 置 running
        return saved;
    }

    /** 跑完：置终态 + ended_at + 截图/日志/结果。status 缺省视为 done。 */
    @Transactional
    public FlowRun finishRun(Long runId, RunFinishReq req) {
        FlowRun r = runRepo.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("flow_run 不存在：" + runId));
        r.setStatus(req.getStatus() != null && !req.getStatus().isBlank() ? req.getStatus() : "done");
        r.setEndedAt(LocalDateTime.now());
        if (req.getResultJson() != null) r.setResultJson(req.getResultJson());
        if (req.getScreenshotUrls() != null) r.setScreenshotUrls(req.getScreenshotUrls());
        if (req.getLogExcerpt() != null) r.setLogExcerpt(req.getLogExcerpt());
        if (req.getErrorMsg() != null) r.setErrorMsg(req.getErrorMsg());
        FlowRun saved = runRepo.save(r);
        periodRepo.findById(r.getFlowPeriodId()).ifPresent(this::recomputeStages);
        return saved;
    }

    // ═══════════════════════ FN-FLOW-03：st_* 聚合 + 预申报 gate ═══════════════════════

    /** 国税 vs 个税分流：category 以 iit 开头 → 个税（st_declare_iit），否则国税（st_declare_vat）。 */
    private static boolean isIit(String category) {
        return category != null && category.startsWith("iit");
    }

    /**
     * 明细行 status 最差值聚合排名：failed/timeout(4) &gt; running/manual(3) &gt; pending(2) &gt; done/exempt(1)；
     * skipped/na/未知(0) 为中性「不拉低」。null 视为 pending。
     */
    private static int statusRank(String s) {
        if (s == null) return 2;
        return switch (s) {
            case "failed", "timeout" -> 4;
            case "running", "manual" -> 3;
            case "pending" -> 2;
            case "done", "exempt" -> 1;
            default -> 0; // skipped / na / 未知 → 中性
        };
    }

    private static String rankToStatus(int worst) {
        return switch (worst) {
            case 4 -> "failed";
            case 3 -> "running";
            case 2 -> "pending";
            case 1 -> "done";
            default -> "skipped"; // 全中性
        };
    }

    /** 明细行最差值。无行 → null（不改 st_*，保持 pending 默认）；全中性 → skipped。 */
    private static String aggregate(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) return null;
        int worst = 0;
        for (String s : statuses) worst = Math.max(worst, statusRank(s));
        return rankToStatus(worst);
    }

    /** 缴款 pay_status → 标准 status 词表（paid=done/partial=running/unpaid=pending/na=na）。 */
    private static String payStatusToStd(String payStatus) {
        if (payStatus == null) return "pending";
        return switch (payStatus) {
            case "paid" -> "done";
            case "partial" -> "running";
            case "unpaid" -> "pending";
            default -> payStatus; // na / 已是标准词 → 原样（na 由 statusRank 视为中性）
        };
    }

    /** 无明细阶段：取该 stage 最新一条 flow_run 终态映射为 st_*；无 run → null（不改）。 */
    private String runStageStatus(Long flowPeriodId, String stage) {
        List<FlowRun> runs = runRepo.findByFlowPeriodIdAndStageOrderByIdDesc(flowPeriodId, stage);
        if (runs.isEmpty()) return null;
        String s = runs.get(0).getStatus();
        if (s == null) return null;
        return "timeout".equals(s) ? "failed" : s; // running/done/failed/skipped 原样
    }

    private static void apply(java.util.function.Consumer<String> setter, String val) {
        if (val != null) setter.accept(val);
    }

    /**
     * 重算主轴 11 个 st_*（§三.3）：有明细表的阶段（collect/declare_vat/declare_iit/payment）取明细行最差值；
     * 无明细表的阶段（login/verify/init/book/carry/predeclare/archive）取该 stage 最新 flow_run 终态。
     * 各阶段无数据时保持原值（pending 默认）。<b>不触碰 task_period。</b>
     */
    @Transactional
    public FlowPeriod recomputeStages(FlowPeriod fp) {
        Long id = fp.getId();

        // 明细表阶段
        apply(fp::setStCollect, aggregate(
                collectRepo.findByFlowPeriodIdOrderByIdAsc(id).stream().map(FlowCollectItem::getStatus).toList()));
        List<FlowDeclareItem> decl = declareRepo.findByFlowPeriodIdOrderByIdAsc(id);
        apply(fp::setStDeclareVat, aggregate(
                decl.stream().filter(d -> !isIit(d.getCategory())).map(FlowDeclareItem::getStatus).toList()));
        apply(fp::setStDeclareIit, aggregate(
                decl.stream().filter(d -> isIit(d.getCategory())).map(FlowDeclareItem::getStatus).toList()));
        apply(fp::setStPayment, aggregate(
                paymentRepo.findByFlowPeriodIdOrderByIdAsc(id).stream()
                        .map(p -> payStatusToStd(p.getPayStatus())).toList()));

        // 无明细阶段（flow_run 终态驱动）
        apply(fp::setStLogin, runStageStatus(id, "login"));
        apply(fp::setStVerify, runStageStatus(id, "verify"));
        apply(fp::setStInit, runStageStatus(id, "init"));
        apply(fp::setStBook, runStageStatus(id, "book"));
        apply(fp::setStCarry, runStageStatus(id, "carry"));
        apply(fp::setStPredeclare, runStageStatus(id, "predeclare"));
        apply(fp::setStArchive, runStageStatus(id, "archive"));

        return periodRepo.save(fp);
    }

    /** 按 (account_set_id, period) 重算（手动/补算入口）。主轴不存在返 null。 */
    @Transactional
    public FlowPeriod recomputeStagesByAccPeriod(Long accountSetId, String period) {
        FlowPeriod p = periodRepo.findByAccountSetIdAndPeriod(accountSetId, period).orElse(null);
        return p == null ? null : recomputeStages(p);
    }

    /**
     * 预申报财务确认 gate（报税前闸门，防重报）：写 predeclare_confirmed/_at/_by。
     * confirmed 缺省=true；置 false 时清空 _at（撤销确认）。
     */
    @Transactional
    public FlowPeriod confirmPredeclare(Long flowPeriodId, Boolean confirmed, String by) {
        FlowPeriod p = periodRepo.findById(flowPeriodId)
                .orElseThrow(() -> new IllegalArgumentException("flow_period 不存在：" + flowPeriodId));
        boolean c = confirmed == null || confirmed;
        p.setPredeclareConfirmed(c);
        p.setPredeclareConfirmedAt(c ? LocalDateTime.now() : null);
        p.setPredeclareConfirmedBy(by);
        return periodRepo.save(p);
    }
}
