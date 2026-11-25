package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaskVatPre;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskVatPreRepository extends JpaRepository<TaskVatPre, Long> {

    List<TaskVatPre> findByTaskPeriodId(Long taskPeriodId);

    List<TaskVatPre> findByTaskPeriodIdAndStatus(Long taskPeriodId, String status);

    Optional<TaskVatPre> findByTaskPeriodIdAndSeqNo(Long taskPeriodId, String seqNo);

    void deleteByTaskPeriodId(Long taskPeriodId);
}
