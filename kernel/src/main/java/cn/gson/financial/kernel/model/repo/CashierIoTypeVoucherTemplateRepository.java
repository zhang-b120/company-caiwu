package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.CashierIoTypeVoucherTemplate;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CashierIoTypeVoucherTemplateRepository extends JpaRepository<CashierIoTypeVoucherTemplate, PrimaryKey> {

    List<CashierIoTypeVoucherTemplate> findByAccountSetsIdAndIoTypeId(Long accountSetsId, Long ioTypeId);
}
