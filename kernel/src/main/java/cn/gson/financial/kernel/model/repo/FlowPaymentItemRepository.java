package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.FlowPaymentItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** FN-FLOW-02：flow_payment_item 仓库。幂等键 (flow_period_id, tax_category, tax_name)。 */
public interface FlowPaymentItemRepository extends JpaRepository<FlowPaymentItem, Long> {

    Optional<FlowPaymentItem> findByFlowPeriodIdAndTaxCategoryAndTaxName(
            Long flowPeriodId, String taxCategory, String taxName);

    List<FlowPaymentItem> findByFlowPeriodIdOrderByIdAsc(Long flowPeriodId);
}
