package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaskVerifySub;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskVerifySubRepository extends JpaRepository<TaskVerifySub, Long> {

    List<TaskVerifySub> findByTaskPeriodId(Long taskPeriodId);

    Optional<TaskVerifySub> findByTaskPeriodIdAndSubType(Long taskPeriodId, String subType);

    void deleteByTaskPeriodId(Long taskPeriodId);
}
