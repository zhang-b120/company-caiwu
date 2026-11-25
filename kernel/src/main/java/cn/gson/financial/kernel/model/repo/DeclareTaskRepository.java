package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.DeclareTask;
import cn.gson.financial.kernel.model.enums.DeclareTaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

/**
 * FN-03 申报任务 Repository
 */
public interface DeclareTaskRepository extends JpaRepository<DeclareTask, Long>,
        JpaSpecificationExecutor<DeclareTask> {

    List<DeclareTask> findByStatus(DeclareTaskStatus status);

    Page<DeclareTask> findByStatus(DeclareTaskStatus status, Pageable pageable);

    Page<DeclareTask> findByStatusAndCustomerId(DeclareTaskStatus status, Long customerId, Pageable pageable);

    Page<DeclareTask> findByCustomerId(Long customerId, Pageable pageable);

    Page<DeclareTask> findByAccountSetsId(Long accountSetsId, Pageable pageable);

    Page<DeclareTask> findByAccountSetsIdAndStatus(Long accountSetsId, DeclareTaskStatus status, Pageable pageable);
}
