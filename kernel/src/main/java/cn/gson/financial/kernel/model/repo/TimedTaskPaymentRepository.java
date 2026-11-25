package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TimedTaskPayment;
import cn.gson.financial.kernel.model.entity.TimedTaskTax;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimedTaskPaymentRepository extends JpaRepository<TimedTaskPayment, Long> {
}
