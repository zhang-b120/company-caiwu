package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.InvoiceCollectRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * FN-LIVE-T3 / FN-U（140）开票收集请求 Repository —— collect 单表统一开票任务。
 */
public interface InvoiceCollectRequestRepository
        extends JpaRepository<InvoiceCollectRequest, Long> {

    /**
     * 幂等：按订单号查已存条目，重复提交时返回已有回执而不是新建。
     */
    Optional<InvoiceCollectRequest> findByOrderNo(String orderNo);

    /**
     * FN-U-T3：拉 BT 可领的待开票任务。
     * <p>只返"可领"的：{@code PENDING_ISSUE} 且 {@code fail_count < maxFail}
     * 且（{@code locked_at} 为空 或 已过期 {@code locked_at < lockExpireBefore}）。
     * 按创建时间升序（先进先开），{@link Pageable} 控制 size。
     * <p>FN-Q（223）可选按销方账套过滤：{@code accountSetsId} 传 null → 全局（行为不变）；
     * 传具体值 → 只返该账套，用 {@code (:param IS NULL OR ...)} 惯用法（同 {@link #findIssuedFiltered}）。
     */
    @Query("SELECT c FROM InvoiceCollectRequest c "
            + "WHERE c.status = 'PENDING_ISSUE' "
            + "AND c.failCount < :maxFail "
            + "AND (c.lockedAt IS NULL OR c.lockedAt < :lockExpireBefore) "
            + "AND (:accountSetsId IS NULL OR c.accountSetsId = :accountSetsId) "
            + "ORDER BY c.createdAt ASC, c.id ASC")  // FN-FIX-...-TASKS-PAGING: 加 id 兜底确保翻页稳定排序
    List<InvoiceCollectRequest> findClaimableTasks(@Param("maxFail") int maxFail,
                                                   @Param("lockExpireBefore") LocalDateTime lockExpireBefore,
                                                   @Param("accountSetsId") Long accountSetsId,
                                                   Pageable pageable);

    /**
     * FN-FIX-INVOICE-COLLECT-TASKS-PAGING：可领待开票任务总数（与 {@link #findClaimableTasks} 同筛选条件），
     * 供 /tasks 返回 total 用（翻页终止判断 + 避免超单页静默漏开）。
     */
    @Query("SELECT COUNT(c) FROM InvoiceCollectRequest c "
            + "WHERE c.status = 'PENDING_ISSUE' "
            + "AND c.failCount < :maxFail "
            + "AND (c.lockedAt IS NULL OR c.lockedAt < :lockExpireBefore)")
    long countClaimableTasks(@Param("maxFail") int maxFail,
                             @Param("lockExpireBefore") LocalDateTime lockExpireBefore);

    /**
     * FN-U-T3：原子领取（防两端重复真开的关键）。
     * <p>双条件 UPDATE：{@code status='PENDING_ISSUE'} 且（锁空 或 已过期）。影响行数=1 → 领到；=0 → 别人已领/已不是待开票。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE InvoiceCollectRequest c "
            + "SET c.lockOwner = :owner, c.lockedAt = :now, c.lastAttemptAt = :now "
            + "WHERE c.orderNo = :orderNo "
            + "AND c.status = 'PENDING_ISSUE' "
            + "AND (c.lockedAt IS NULL OR c.lockedAt < :lockExpireBefore)")
    int claimTask(@Param("orderNo") String orderNo,
                  @Param("owner") String owner,
                  @Param("now") LocalDateTime now,
                  @Param("lockExpireBefore") LocalDateTime lockExpireBefore);

    /**
     * FN-LIST-T1：按账套分页查开票单，按创建时间倒序（走 idx_account_created）。
     */
    Page<InvoiceCollectRequest> findByAccountSetsIdOrderByCreatedAtDesc(Long accountSetsId, Pageable pageable);

    Page<InvoiceCollectRequest> findByAccountSetsIdAndStatusOrderByCreatedAtDesc(
            Long accountSetsId, String status, Pageable pageable);

    /**
     * FN-SYNC-T1（159）：按一批订单号批量查 collect 状态，给收银端订单列表 overlay 用。
     * <p>{@code WHERE order_no IN (:orderNos)}，走 {@code order_no} 唯一键（无需新迁移）。
     * 空集合 / 全部查不到 → 返回空 List。
     */
    List<InvoiceCollectRequest> findByOrderNoIn(Collection<String> orderNos);

    /**
     * FN-FAIL-T1a（175）：按状态分页查（失败列表端点用 {@code status='ISSUE_FAILED'}）。
     * <p>排序由 {@link Pageable} 控制（控制器按 {@code lastAttemptAt} 倒序，NULL 自然排末尾，走 {@code idx_status_created}）。
     * 与 {@link #findClaimableTasks} 区别：本方法只按 status 取，不带 maxFail/锁过滤（终态本就不参与领取）。
     */
    List<InvoiceCollectRequest> findByStatus(String status, Pageable pageable);

    /**
     * FN-ISSUED-T1（183）：已开发票列表动态过滤——{@code status} 固定 + 可选账套 + 可选 issued_at 闭区间。
     * <p>用 {@code (:param IS NULL OR ...)} 惯用法动态拼，等价 Specification 但无需 DB 即可单测；
     * 三个过滤项可任意组合、可只传一端（另一端传 NULL）。排序由 {@link Pageable} 控制
     * （控制器按 {@code issuedAt} 倒序，NULL 自然排末尾，走 {@code idx_status_issued}）。
     * <p>无任何过滤时控制器退化走 {@link #findByStatus}，本方法仅在带账套/日期时调用。
     */
    @Query("SELECT c FROM InvoiceCollectRequest c "
            + "WHERE c.status = :status "
            + "AND (:accountSetsId IS NULL OR c.accountSetsId = :accountSetsId) "
            + "AND (:issuedFrom IS NULL OR c.issuedAt >= :issuedFrom) "
            + "AND (:issuedTo IS NULL OR c.issuedAt <= :issuedTo)")
    List<InvoiceCollectRequest> findIssuedFiltered(@Param("status") String status,
                                                   @Param("accountSetsId") Long accountSetsId,
                                                   @Param("issuedFrom") LocalDateTime issuedFrom,
                                                   @Param("issuedTo") LocalDateTime issuedTo,
                                                   Pageable pageable);
}
