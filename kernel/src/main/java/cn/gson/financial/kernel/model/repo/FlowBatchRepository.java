package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.FlowBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** FN-FLOW-BATCH-STATE：桌面批量批次仓库。 */
public interface FlowBatchRepository extends JpaRepository<FlowBatch, Long> {

    /** 某账期最近一个批次（看板默认拉最新）。 */
    Optional<FlowBatch> findFirstByPeriodOrderByIdDesc(String period);
}
