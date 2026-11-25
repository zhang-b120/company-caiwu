package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.AccountSetsIndividualTaxStatusItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountSetsIndividualTaxStatusItemRepository extends JpaRepository<AccountSetsIndividualTaxStatusItem, Long> {

    List<AccountSetsIndividualTaxStatusItem> findByIndividualTaxStatusId(Long individualTaxStatusId);

    List<AccountSetsIndividualTaxStatusItem> findByAccountSetsId(Long accountSetsId);
}

