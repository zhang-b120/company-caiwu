package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.FlowBatchItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** FN-FLOW-BATCH-STATE：批次明细仓库。幂等键 (batch_id, account_set_id, period)。 */
public interface FlowBatchItemRepository extends JpaRepository<FlowBatchItem, Long> {

    List<FlowBatchItem> findByBatchIdOrderByIdAsc(Long batchId);

    Optional<FlowBatchItem> findByBatchIdAndAccountSetIdAndPeriod(Long batchId, Long accountSetId, String period);
}
