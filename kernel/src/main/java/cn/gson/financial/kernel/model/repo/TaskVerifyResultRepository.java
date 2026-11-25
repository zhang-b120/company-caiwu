package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaskVerifyResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TaskVerifyResultRepository extends JpaRepository<TaskVerifyResult, Long> {

    Optional<TaskVerifyResult> findByTaskPeriodId(Long taskPeriodId);

    void deleteByTaskPeriodId(Long taskPeriodId);
}
