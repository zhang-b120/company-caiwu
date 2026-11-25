package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.FlowPeriod;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** FN-FLOW-02：flow_period 主轴仓库。幂等键 (account_set_id, period)。 */
public interface FlowPeriodRepository extends JpaRepository<FlowPeriod, Long> {

    Optional<FlowPeriod> findByAccountSetIdAndPeriod(Long accountSetId, String period);

    /** FN-PIPELINE-COUNTS-BY-USER：批量取一批账套某期的主轴行（流水线计数全量统计用）。 */
    List<FlowPeriod> findByAccountSetIdInAndPeriod(List<Long> accountSetIds, String period);

    /**
     * FN-RPA-QUEUE-API：当期「该报未报」账套——预申报已确认 + 国税申报未完成且未在跑（防重复派）。
     * st_declare_vat NOT IN (done/running/skipped/na)：排除已完成、已被领、免报；pending/failed(重试) 入选。
     * 按 id 升序 + Pageable 限流（一次取 3-4 条）。
     */
    @Query("SELECT f FROM FlowPeriod f WHERE f.period = :period AND f.predeclareConfirmed = true "
            + "AND f.stDeclareVat NOT IN ('done','running','skipped','na') ORDER BY f.id ASC")
    List<FlowPeriod> findPendingDeclare(@Param("period") String period, Pageable pageable);

    /**
     * FN-QUEUE-CLAIM-ATOMIC：行级写锁取主轴行——declare/full-flow 认领的串行化点，
     * 多 poller 并发同户时只有持锁者能认领（锁内复检 + 写），统一防重口径。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM FlowPeriod f WHERE f.id = :id")
    Optional<FlowPeriod> findByIdForUpdate(@Param("id") Long id);
}
