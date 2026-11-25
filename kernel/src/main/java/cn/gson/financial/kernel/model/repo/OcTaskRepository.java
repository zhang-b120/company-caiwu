package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.OcTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OcTaskRepository extends JpaRepository<OcTask, Long> {

    List<OcTask> findByStatusOrderByCreatedAtAsc(String status);

    /** 按省份聚合 + 创建时间排序，保证同省份任务连续处理，减少省份切换开销 */
    @Query("SELECT o FROM OcTask o LEFT JOIN AccountSets a ON o.accountSetId = a.id " +
           "WHERE o.status = :status ORDER BY a.province ASC, o.createdAt ASC")
    List<OcTask> findByStatusOrderByProvinceAscCreatedAtAsc(@Param("status") String status);

    List<OcTask> findByStatusInOrderByCreatedAtAsc(List<String> statuses);

    Optional<OcTask> findTopByAccountSetIdAndTaskTypeAndPeriodOrderByCreatedAtDesc(
            Long accountSetId, String taskType, String period);

    List<OcTask> findByAccountSetId(Long accountSetId);

    List<OcTask> findByAccountSetIdAndPeriod(Long accountSetId, String period);

    /** 查询指定账期内指定状态的 OC 任务，按创建时间降序 */
    List<OcTask> findByPeriodAndStatusInOrderByCreatedAtDesc(String period, List<String> statuses);

    /** 查询指定账期内最近完成/失败的 OC 任务（按完成时间降序） */
    @Query("SELECT o FROM OcTask o WHERE o.period = :period AND o.status IN :statuses AND o.finishedAt IS NOT NULL ORDER BY o.finishedAt DESC")
    List<OcTask> findRecentFinished(@Param("period") String period, @Param("statuses") List<String> statuses);

    List<OcTask> findByAccountSetIdAndPeriodAndTaskTypeIn(Long accountSetId, String period, List<String> taskTypes);

    /** 查询指定账期内指定账套列表中处于活跃状态的 oc_task（孤立 running 检测用） */
    List<OcTask> findByPeriodAndAccountSetIdInAndStatusIn(String period, List<Long> accountSetIds, List<String> statuses);

    @Modifying
    @Query("DELETE FROM OcTask o WHERE o.accountSetId = :accountSetId AND o.period = :period")
    int deleteByAccountSetIdAndPeriod(@Param("accountSetId") Long accountSetId, @Param("period") String period);

    @Modifying
    @Query("DELETE FROM OcTask o WHERE o.accountSetId = :accountSetId AND o.period = :period AND o.taskType IN :taskTypes")
    int deleteByAccountSetIdAndPeriodAndTaskTypeIn(@Param("accountSetId") Long accountSetId, @Param("period") String period, @Param("taskTypes") List<String> taskTypes);

    @Modifying
    @Query("DELETE FROM OcTask o WHERE o.accountSetId = :accountSetId AND o.taskType IN :taskTypes")
    int deleteByAccountSetIdAndTaskTypeIn(@Param("accountSetId") Long accountSetId, @Param("taskTypes") List<String> taskTypes);

    /**
     * 原子性地将指定 ID 列表中仍处于 queued 状态的任务标记为 running，并记录领取的 OC 实例编号。
     * 通过 WHERE status = 'queued' 防止并发领取同一任务。
     */
    @Modifying
    @Query("UPDATE OcTask o SET o.status = 'running', o.ocInstanceId = :ocInstanceId, o.startedAt = :startedAt " +
           "WHERE o.id IN :ids AND o.status = 'queued'")
    int markAsRunning(@Param("ids") List<Long> ids, @Param("ocInstanceId") String ocInstanceId,
                      @Param("startedAt") java.time.LocalDateTime startedAt);

    /** 查找指定 taskPeriod 下指定 taskType 和 status 的任务（用于解锁 blocked oc_task） */
    Optional<OcTask> findFirstByTaskPeriodIdAndTaskTypeAndStatus(Long taskPeriodId, String taskType, String status);

    /**
     * 查找指定 taskPeriod 下指定 taskType、状态在给定集合内的最早任务。
     * 下发幂等复用用：避免在已有未完成（blocked/queued/running）任务时重复创建第二条。
     */
    Optional<OcTask> findFirstByTaskPeriodIdAndTaskTypeAndStatusInOrderByCreatedAtAsc(
            Long taskPeriodId, String taskType, List<String> statuses);

    List<OcTask> findByTaskPeriodIdAndStatus(Long taskPeriodId, String status);
}
