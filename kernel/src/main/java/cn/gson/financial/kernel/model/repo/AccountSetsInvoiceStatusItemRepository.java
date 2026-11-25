package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.AccountSetsInvoiceStatusItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountSetsInvoiceStatusItemRepository extends JpaRepository<AccountSetsInvoiceStatusItem, Long> {

    List<AccountSetsInvoiceStatusItem> findByInvoiceStatusId(Long invoiceStatusId);

    List<AccountSetsInvoiceStatusItem> findByInvoiceStatusIdAndDeleted(Long invoiceStatusId, Integer deleted);

    List<AccountSetsInvoiceStatusItem> findByAccountSetsId(Long accountSetsId);
    
    List<AccountSetsInvoiceStatusItem> findByAccountSetsIdIn(List<Long> accountSetsIds);

    void deleteByInvoiceStatusId(Long invoiceStatusId);
}

