package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaskTaxItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskTaxItemRepository extends JpaRepository<TaskTaxItem, Long> {

    List<TaskTaxItem> findByTaskPeriodId(Long taskPeriodId);

    Optional<TaskTaxItem> findByTaskPeriodIdAndTaxName(Long taskPeriodId, String taxName);

    void deleteByTaskPeriodId(Long taskPeriodId);
}
