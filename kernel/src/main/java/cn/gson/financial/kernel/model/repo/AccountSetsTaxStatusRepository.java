package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.AccountSetsTaxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccountSetsTaxStatusRepository extends JpaRepository<AccountSetsTaxStatus, Long> {

    Optional<AccountSetsTaxStatus> findByAccountSetsIdAndPeriod(Long accountSetsId, LocalDate period);

    Optional<AccountSetsTaxStatus> findByAccountSetsIdAndPeriodAndDeleted(Long accountSetsId, LocalDate period, Integer deleted);

    List<AccountSetsTaxStatus> findByAccountSetsIdIn(List<Long> accountSetsIds);
}

