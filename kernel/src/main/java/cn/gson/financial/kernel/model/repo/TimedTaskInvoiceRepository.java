package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TimedTaskInvoice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimedTaskInvoiceRepository extends JpaRepository<TimedTaskInvoice, Long> {
}
