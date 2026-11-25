package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.AccountSetsTaxStatusItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountSetsTaxStatusItemRepository extends JpaRepository<AccountSetsTaxStatusItem, Long> {

    List<AccountSetsTaxStatusItem> findByTaxStatusId(Long taxStatusId);

    List<AccountSetsTaxStatusItem> findByTaxStatusIdAndDeleted(Long taxStatusId, Integer deleted);

    List<AccountSetsTaxStatusItem> findByAccountSetsId(Long accountSetsId);
}

