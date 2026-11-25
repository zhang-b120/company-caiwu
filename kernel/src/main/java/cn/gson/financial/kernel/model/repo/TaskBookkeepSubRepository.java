package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaskBookkeepSub;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskBookkeepSubRepository extends JpaRepository<TaskBookkeepSub, Long> {

    List<TaskBookkeepSub> findByTaskPeriodId(Long taskPeriodId);

    List<TaskBookkeepSub> findByTaskPeriodIdIn(List<Long> taskPeriodIds);

    Optional<TaskBookkeepSub> findByTaskPeriodIdAndSubType(Long taskPeriodId, String subType);

    void deleteByTaskPeriodId(Long taskPeriodId);
}
