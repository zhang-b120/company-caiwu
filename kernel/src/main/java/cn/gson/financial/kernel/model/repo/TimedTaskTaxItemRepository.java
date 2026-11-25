package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TimedTaskTaxItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimedTaskTaxItemRepository extends JpaRepository<TimedTaskTaxItem, Long> {
}
