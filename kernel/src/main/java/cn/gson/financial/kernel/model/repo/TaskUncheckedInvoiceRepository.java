package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaskUncheckedInvoice;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskUncheckedInvoiceRepository extends JpaRepository<TaskUncheckedInvoice, PrimaryKey> {

    List<TaskUncheckedInvoice> findByAccountSetsId(Long accountSetsId);

    void deleteByAccountSetsId(Long accountSetsId);

    void deleteByOcTaskId(Long ocTaskId);
}
