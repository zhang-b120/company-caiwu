package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.FlowCollectItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** FN-FLOW-02：flow_collect_item 仓库。幂等键 (flow_period_id, source)。 */
public interface FlowCollectItemRepository extends JpaRepository<FlowCollectItem, Long> {

    Optional<FlowCollectItem> findByFlowPeriodIdAndSource(Long flowPeriodId, String source);

    List<FlowCollectItem> findByFlowPeriodIdOrderByIdAsc(Long flowPeriodId);
}
