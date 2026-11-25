package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaskIitItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskIitItemRepository extends JpaRepository<TaskIitItem, Long> {

    List<TaskIitItem> findByTaskPeriodId(Long taskPeriodId);

    List<TaskIitItem> findByTaskPeriodIdIn(List<Long> taskPeriodIds);

    Optional<TaskIitItem> findByTaskPeriodIdAndIitType(Long taskPeriodId, String iitType);

    void deleteByTaskPeriodId(Long taskPeriodId);
}
