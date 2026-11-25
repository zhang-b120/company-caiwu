package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.FlowRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** FN-FLOW-02：flow_run 执行流水仓库（每跑一次 RPA 一行，无 UNIQUE）。 */
public interface FlowRunRepository extends JpaRepository<FlowRun, Long> {

    List<FlowRun> findByFlowPeriodIdOrderByIdDesc(Long flowPeriodId);

    List<FlowRun> findByFlowPeriodIdAndStageOrderByIdDesc(Long flowPeriodId, String stage);

    /** FN-QUEUE-FULLFLOW：在跑的某 stage 流水（认领去重用，如 stage='full-flow' status='running'）。 */
    List<FlowRun> findByFlowPeriodIdAndStageAndStatus(Long flowPeriodId, String stage, String status);
}
