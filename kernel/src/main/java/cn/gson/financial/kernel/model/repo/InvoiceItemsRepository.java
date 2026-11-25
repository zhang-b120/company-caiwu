package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.InvoiceItems;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvoiceItemsRepository extends JpaRepository<InvoiceItems, PrimaryKey> {

    List<InvoiceItems> findByInvoiceIdIn(List<Long> invoiceIds);
}
