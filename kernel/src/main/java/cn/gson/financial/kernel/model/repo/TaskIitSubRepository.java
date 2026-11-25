package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaskIitSub;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskIitSubRepository extends JpaRepository<TaskIitSub, Long> {

    List<TaskIitSub> findByTaskPeriodId(Long taskPeriodId);

    Optional<TaskIitSub> findByTaskPeriodIdAndSubType(Long taskPeriodId, String subType);
}
