package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.InvoiceBusinessType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceBusinessTypeRepository extends JpaRepository<InvoiceBusinessType, PrimaryKey> {
}
