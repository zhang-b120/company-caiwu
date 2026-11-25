package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaskInitResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TaskInitResultRepository extends JpaRepository<TaskInitResult, Long> {

    Optional<TaskInitResult> findByTaskPeriodId(Long taskPeriodId);
}
