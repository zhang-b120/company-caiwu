package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaskPaymentItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskPaymentItemRepository extends JpaRepository<TaskPaymentItem, Long> {

    List<TaskPaymentItem> findByTaskPeriodId(Long taskPeriodId);

    List<TaskPaymentItem> findByTaskPeriodIdIn(List<Long> taskPeriodIds);

    void deleteByTaskPeriodId(Long taskPeriodId);

    void deleteByTaskPeriodIdAndTaxCodeStartingWith(Long taskPeriodId, String taxCodePrefix);
}
