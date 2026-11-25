package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaskTaxAnnualItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskTaxAnnualItemRepository extends JpaRepository<TaskTaxAnnualItem, Long> {

    List<TaskTaxAnnualItem> findByTaskPeriodId(Long taskPeriodId);

    List<TaskTaxAnnualItem> findByTaskPeriodIdIn(List<Long> taskPeriodIds);

    Optional<TaskTaxAnnualItem> findByAccountSetIdAndTaxYear(Long accountSetId, String taxYear);

    List<TaskTaxAnnualItem> findByAccountSetIdInAndTaxYear(List<Long> accountSetIds, String taxYear);

    Optional<TaskTaxAnnualItem> findByOcTaskId(Long ocTaskId);

    boolean existsByAccountSetIdAndTaxYear(Long accountSetId, String taxYear);

    void deleteByTaskPeriodId(Long taskPeriodId);

    void deleteByAccountSetIdAndTaxYear(Long accountSetId, String taxYear);
}
