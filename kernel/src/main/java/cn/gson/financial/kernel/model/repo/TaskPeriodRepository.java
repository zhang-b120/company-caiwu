package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaskPeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TaskPeriodRepository extends JpaRepository<TaskPeriod, Long> {

    Optional<TaskPeriod> findByAccountSetIdAndPeriod(Long accountSetId, String period);

    List<TaskPeriod> findByPeriod(String period);

    List<TaskPeriod> findByAccountSetIdIn(List<Long> accountSetIds);

    @Query("SELECT t FROM TaskPeriod t WHERE t.period = :period AND t.accountSetId IN :accountSetIds")
    List<TaskPeriod> findByPeriodAndAccountSetIdIn(@Param("period") String period,
                                                    @Param("accountSetIds") List<Long> accountSetIds);

    List<TaskPeriod> findByAccountSetId(Long accountSetId);

    void deleteByAccountSetIdAndPeriod(Long accountSetId, String period);

    boolean existsByAccountSetIdAndStepInit(Long accountSetId, String stepInit);

    @Query("SELECT DISTINCT t.accountSetId FROM TaskPeriod t WHERE t.stepInit = :stepInit AND t.accountSetId IN :accountSetIds")
    List<Long> findAccountSetIdsByStepInitAndAccountSetIdIn(@Param("stepInit") String stepInit,
                                                             @Param("accountSetIds") List<Long> accountSetIds);

    /**
     * 一条 SQL 同时取回当期 TaskPeriod + ever_done_init 标记，替代原来的两次 IN 查询。
     * 返回 Object[]：[0] = TaskPeriod, [1] = Boolean(是否曾有任何账期 stepInit='done')
     */
    @Query("""
        SELECT tp,
               CASE WHEN EXISTS (
                   SELECT 1 FROM TaskPeriod tp2
                   WHERE tp2.accountSetId = tp.accountSetId AND tp2.stepInit = 'done'
               ) THEN true ELSE false END
        FROM TaskPeriod tp
        WHERE tp.period = :period AND tp.accountSetId IN :accountSetIds
        """)
    List<Object[]> findByPeriodWithEverDoneInit(@Param("period") String period,
                                                 @Param("accountSetIds") List<Long> accountSetIds);

}
