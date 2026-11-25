package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.FlowClaim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** FN-FLOW-CLAIM：执行锁/登录锁仓库。唯一键 (account_set_id, period, stage)。 */
public interface FlowClaimRepository extends JpaRepository<FlowClaim, Long> {

    Optional<FlowClaim> findByAccountSetIdAndPeriodAndStage(Long accountSetId, String period, String stage);
}
