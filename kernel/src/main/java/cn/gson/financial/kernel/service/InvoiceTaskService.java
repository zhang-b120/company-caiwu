package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.event.InvoiceTaskCompletedEvent;
import cn.gson.financial.kernel.model.entity.InvoiceTask;
import cn.gson.financial.kernel.model.repo.InvoiceTaskRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * FN-04 开票任务 Service
 * <p>
 * 含完整的状态机校验逻辑（{@link #assertTransition(String, String)}）和业务约束
 * （{@link #assertCanStart(InvoiceTask)} —— 红字必须先 CS_REVIEWING）。
 */
@Service
@RequiredArgsConstructor
public class InvoiceTaskService {

    private final InvoiceTaskRepository invoiceTaskRepository;

    /**
     * FN-05 事件总线：终态（SUCCESS/FAILED/RED_PENDING）时 publishEvent，
     * 由异步监听器（@Async）做 wx 推 PDF / 企微通知销售 / finmaster 建跟进工单。
     */
    private final ApplicationEventPublisher eventPublisher;

    // ═══════════════════════════════════════════════════════════════
    // 状态机：声明合法 transition 集合
    //   key=源状态, value=允许的目标状态集合
    // ═══════════════════════════════════════════════════════════════
    private static final java.util.Map<String, Set<String>> ALLOWED_TRANSITIONS = new java.util.HashMap<>();

    static {
        ALLOWED_TRANSITIONS.put(InvoiceTask.STATUS_CUSTOMER_REQUESTED,
                new HashSet<>(java.util.Arrays.asList(InvoiceTask.STATUS_CS_REVIEWING)));
        // CS_REVIEWING 可以批准 (APPROVED) 或拒绝 (FAILED)
        ALLOWED_TRANSITIONS.put(InvoiceTask.STATUS_CS_REVIEWING,
                new HashSet<>(java.util.Arrays.asList(InvoiceTask.STATUS_APPROVED, InvoiceTask.STATUS_FAILED)));
        ALLOWED_TRANSITIONS.put(InvoiceTask.STATUS_APPROVED,
                new HashSet<>(java.util.Arrays.asList(InvoiceTask.STATUS_EXECUTING)));
        // EXECUTING 可以成功 / 失败 / 红字待开
        ALLOWED_TRANSITIONS.put(InvoiceTask.STATUS_EXECUTING,
                new HashSet<>(java.util.Arrays.asList(
                        InvoiceTask.STATUS_SUCCESS,
                        InvoiceTask.STATUS_FAILED,
                        InvoiceTask.STATUS_RED_PENDING)));
        // SUCCESS / FAILED / RED_PENDING 是终态，不允许迁出
        ALLOWED_TRANSITIONS.put(InvoiceTask.STATUS_SUCCESS, new HashSet<>());
        ALLOWED_TRANSITIONS.put(InvoiceTask.STATUS_FAILED, new HashSet<>());
        ALLOWED_TRANSITIONS.put(InvoiceTask.STATUS_RED_PENDING, new HashSet<>());
    }

    // ═══════════════════════════════════════════════════════════════
    // 基础 CRUD
    // ═══════════════════════════════════════════════════════════════

    public Optional<InvoiceTask> findById(Long id) {
        return invoiceTaskRepository.findById(id);
    }

    @Transactional
    public InvoiceTask create(InvoiceTask task) {
        if (task.getStatus() == null) {
            task.setStatus(InvoiceTask.STATUS_CUSTOMER_REQUESTED);
        }
        if (task.getInvoiceType() == null) {
            task.setInvoiceType("NORMAL");
        }
        if (task.getIsRedInvoice() == null) {
            task.setIsRedInvoice(Boolean.FALSE);
        }
        validateBusinessFields(task);
        return invoiceTaskRepository.save(task);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public InvoiceTask createInNewTransaction(InvoiceTask task) {
        return create(task);
    }

    /**
     * 业务字段基本校验：红字发票必须带 originalInvoiceId（除非显式跳过）。
     */
    private void validateBusinessFields(InvoiceTask task) {
        if (task.getAccountSetsId() == null) {
            throw new IllegalArgumentException("accountSetsId 不能为空");
        }
        if (task.getCustomerId() == null) {
            throw new IllegalArgumentException("customerId 不能为空");
        }
        if (Boolean.TRUE.equals(task.getIsRedInvoice()) && task.getOriginalInvoiceId() == null) {
            throw new IllegalArgumentException("红字发票必须提供 originalInvoiceId（关联原蓝字发票 id）");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 列表查询（Spec + 分页）
    // ═══════════════════════════════════════════════════════════════

    public Page<InvoiceTask> page(String status,
                                  Long customerId,
                                  Long accountSetsId,
                                  Boolean isRedInvoice,
                                  int page, int size) {
        return page(status, customerId, accountSetsId, isRedInvoice, null, page, size);
    }

    /**
     * FN2-T3（135）：加 source 过滤——BT 扫码直开 poller 必须按 {@code source=scan-direct} 拉，
     * 否则会把旧"预览-确认"路径的 APPROVED 任务一起拉走开成真票（safety red line）。
     */
    public Page<InvoiceTask> page(String status,
                                  Long customerId,
                                  Long accountSetsId,
                                  Boolean isRedInvoice,
                                  String source,
                                  int page, int size) {
        Specification<InvoiceTask> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (status != null && !status.isBlank()) {
                ps.add(cb.equal(root.get("status"), status));
            }
            if (customerId != null) {
                ps.add(cb.equal(root.get("customerId"), customerId));
            }
            if (accountSetsId != null) {
                ps.add(cb.equal(root.get("accountSetsId"), accountSetsId));
            }
            if (isRedInvoice != null) {
                ps.add(cb.equal(root.get("isRedInvoice"), isRedInvoice));
            }
            if (source != null && !source.isBlank()) {
                ps.add(cb.equal(root.get("source"), source.trim()));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        PageRequest pr = PageRequest.of(Math.max(0, page - 1), Math.max(1, Math.min(500, size)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return invoiceTaskRepository.findAll(spec, pr);
    }

    // ═══════════════════════════════════════════════════════════════
    // 状态机 transitions
    // ═══════════════════════════════════════════════════════════════

    /**
     * CUSTOMER_REQUESTED → CS_REVIEWING
     */
    @Transactional
    public InvoiceTask startReview(Long id) {
        InvoiceTask t = mustFind(id);
        assertTransition(t.getStatus(), InvoiceTask.STATUS_CS_REVIEWING);
        t.setStatus(InvoiceTask.STATUS_CS_REVIEWING);
        return invoiceTaskRepository.save(t);
    }

    /**
     * CS_REVIEWING → APPROVED 或 CS_REVIEWING → FAILED
     * <p>
     * FN-FIX-04：拒绝路径（approved=false）也是 FAILED 终态，必须 publishEvent
     * InvoiceTaskCompletedEvent，下游（wx 推客户被拒提示 / 销售 / finmaster 工单）
     * 才能跟进。审批通过路径（→ APPROVED 非终态）不发事件。
     */
    @Transactional
    public InvoiceTask review(Long id, Long reviewerId, boolean approved, String comment) {
        InvoiceTask t = mustFind(id);
        String target = approved ? InvoiceTask.STATUS_APPROVED : InvoiceTask.STATUS_FAILED;
        assertTransition(t.getStatus(), target);
        if (reviewerId == null) {
            throw new IllegalArgumentException("reviewerId 不能为空");
        }
        t.setStatus(target);
        t.setReviewerId(reviewerId);
        t.setReviewedAt(LocalDateTime.now());
        if (comment != null) {
            t.setReviewComment(comment);
        }
        if (!approved) {
            // 拒绝时回填一份 errorMsg，方便客户/后台直接看到原因
            String errMsg = "客服拒绝：" + (comment != null && !comment.isBlank() ? comment : "未填写原因");
            t.setErrorMsg(errMsg);
        }
        InvoiceTask saved = invoiceTaskRepository.save(t);
        // FN-FIX-04：拒绝即终态 FAILED → 发完成事件让下游通知客户
        if (!approved) {
            eventPublisher.publishEvent(new InvoiceTaskCompletedEvent(saved));
        }
        return saved;
    }

    /**
     * APPROVED → EXECUTING
     * <p>
     * 红字发票额外约束：reviewer_id 必须非空（即必须真的经过客服审核），
     * 防止"先批后审"绕过红字风控。
     */
    @Transactional
    public InvoiceTask start(Long id, String executor) {
        InvoiceTask t = mustFind(id);
        assertTransition(t.getStatus(), InvoiceTask.STATUS_EXECUTING);
        assertCanStart(t);
        t.setStatus(InvoiceTask.STATUS_EXECUTING);
        if (executor != null && !executor.isBlank()) {
            t.setExecutor(executor);
        }
        t.setSubmittedAt(LocalDateTime.now());
        return invoiceTaskRepository.save(t);
    }

    /**
     * EXECUTING → SUCCESS/FAILED/RED_PENDING
     * <p>
     * FN-FIX-01：SUCCESS 时支持 BT 回写 pdfUrl，后续 FN-05 listener 会把 pdfUrl
     * 透传给下游（wx 推 PDF / finmaster 归档）。pdfUrl 仅在 SUCCESS 写入。
     */
    @Transactional
    public InvoiceTask complete(Long id,
                                String resultStatus,
                                String invoiceCode,
                                String invoiceNumber,
                                String errorMsg,
                                String redPendingReason,
                                String pdfUrl) {
        InvoiceTask t = mustFind(id);
        if (resultStatus == null) {
            throw new IllegalArgumentException("status 不能为空");
        }
        // 仅允许这 3 个终态
        if (!InvoiceTask.STATUS_SUCCESS.equals(resultStatus)
                && !InvoiceTask.STATUS_FAILED.equals(resultStatus)
                && !InvoiceTask.STATUS_RED_PENDING.equals(resultStatus)) {
            throw new IllegalStateException("非法的结果状态：" + resultStatus
                    + "，必须是 SUCCESS/FAILED/RED_PENDING 之一");
        }
        assertTransition(t.getStatus(), resultStatus);
        t.setStatus(resultStatus);
        if (InvoiceTask.STATUS_SUCCESS.equals(resultStatus)) {
            t.setInvoiceCode(invoiceCode);
            t.setInvoiceNumber(invoiceNumber);
            // FN-FIX-01：SUCCESS 时 BT 回写 PDF URL
            if (pdfUrl != null && !pdfUrl.isBlank()) {
                t.setPdfUrl(pdfUrl.trim());
            }
        }
        if (errorMsg != null) {
            t.setErrorMsg(errorMsg);
        }
        if (InvoiceTask.STATUS_RED_PENDING.equals(resultStatus) && redPendingReason != null) {
            t.setRedPendingReason(redPendingReason);
        }
        InvoiceTask saved = invoiceTaskRepository.save(t);
        // FN-05 终态触发完成事件 — wx PDF 推送 / 企微通知销售 / finmaster 工单都靠这个
        eventPublisher.publishEvent(new InvoiceTaskCompletedEvent(saved));
        return saved;
    }

    /**
     * 向后兼容的 4-arg overload（保留给旧调用方 / 单测）。
     * 等价于 pdfUrl=null 调 6-arg 版本。
     * FN-FIX-01 引入 pdfUrl 后这个 overload 可逐步移除。
     */
    @Transactional
    public InvoiceTask complete(Long id,
                                String resultStatus,
                                String invoiceCode,
                                String invoiceNumber,
                                String errorMsg,
                                String redPendingReason) {
        return complete(id, resultStatus, invoiceCode, invoiceNumber, errorMsg, redPendingReason, null);
    }

    // ═══════════════════════════════════════════════════════════════
    // 状态机校验（package-private，方便单测直接调）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 校验 from→to 是否在 ALLOWED_TRANSITIONS 中。
     * 不合法直接抛 IllegalStateException。
     */
    void assertTransition(String from, String to) {
        Set<String> allowed = ALLOWED_TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new IllegalStateException("非法状态转换：" + from + " → " + to
                    + "（允许：" + (allowed == null ? "[]" : allowed) + "）");
        }
    }

    /**
     * 红字发票额外约束：进入 EXECUTING 前必须真的有 reviewer。
     * 普通发票虽然状态机已强制 APPROVED → EXECUTING，但不检查 reviewer 字段
     * （理论上 review() 已经回填，此处仅做最后兜底）。
     */
    void assertCanStart(InvoiceTask t) {
        if (Boolean.TRUE.equals(t.getIsRedInvoice()) && t.getReviewerId() == null) {
            throw new IllegalStateException(
                    "红字发票必须先经过客服审核（CS_REVIEWING → APPROVED），不允许跳过");
        }
    }

    private InvoiceTask mustFind(Long id) {
        return invoiceTaskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("invoice_task 不存在：" + id));
    }
}
