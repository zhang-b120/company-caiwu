package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.AccountSetsIndividualTaxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccountSetsIndividualTaxStatusRepository extends JpaRepository<AccountSetsIndividualTaxStatus, Long> {

    Optional<AccountSetsIndividualTaxStatus> findByAccountSetsIdAndPeriod(Long accountSetsId, LocalDate period);

    List<AccountSetsIndividualTaxStatus> findByAccountSetsIdIn(List<Long> accountSetsIds);
}

