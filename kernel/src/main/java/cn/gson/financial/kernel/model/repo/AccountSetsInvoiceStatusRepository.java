package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.AccountSetsInvoiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccountSetsInvoiceStatusRepository extends JpaRepository<AccountSetsInvoiceStatus, Long> {

    Optional<AccountSetsInvoiceStatus> findByAccountSetsIdAndPeriod(Long accountSetsId, LocalDate period);

    Optional<AccountSetsInvoiceStatus> findByAccountSetsIdAndPeriodAndDeleted(Long accountSetsId, LocalDate period, Integer deleted);

    List<AccountSetsInvoiceStatus> findByAccountSetsIdIn(List<Long> accountSetsIds);
}

