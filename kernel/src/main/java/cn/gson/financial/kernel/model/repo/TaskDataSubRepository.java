package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaskDataSub;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskDataSubRepository extends JpaRepository<TaskDataSub, Long> {

    List<TaskDataSub> findByTaskPeriodId(Long taskPeriodId);

    Optional<TaskDataSub> findByTaskPeriodIdAndSubType(Long taskPeriodId, String subType);

    List<TaskDataSub> findByTaskPeriodIdIn(List<Long> taskPeriodIds);
}
