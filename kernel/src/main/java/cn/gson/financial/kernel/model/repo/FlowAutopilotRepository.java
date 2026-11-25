package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.FlowAutopilot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** FN-AUTOPILOT-SWITCH：自动做账+报税 开关/白名单仓库。account_set_id=0 为全局总开关。 */
public interface FlowAutopilotRepository extends JpaRepository<FlowAutopilot, Long> {

    Optional<FlowAutopilot> findByAccountSetId(Long accountSetId);

    List<FlowAutopilot> findByEnabledTrue();
}
