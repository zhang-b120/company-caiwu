package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.AccountSetsInitialBalanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountSetsInitialBalanceStatusRepository extends JpaRepository<AccountSetsInitialBalanceStatus, Long> {

    Optional<AccountSetsInitialBalanceStatus> findByAccountSetsId(Long accountSetsId);

    List<AccountSetsInitialBalanceStatus> findByAccountSetsIdIn(List<Long> accountSetsIds);
}

