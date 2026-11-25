package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.FlowDeclareItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** FN-FLOW-02：flow_declare_item 仓库。幂等键 (flow_period_id, category, tax_name)。 */
public interface FlowDeclareItemRepository extends JpaRepository<FlowDeclareItem, Long> {

    Optional<FlowDeclareItem> findByFlowPeriodIdAndCategoryAndTaxName(
            Long flowPeriodId, String category, String taxName);

    List<FlowDeclareItem> findByFlowPeriodIdOrderByIdAsc(Long flowPeriodId);
}
