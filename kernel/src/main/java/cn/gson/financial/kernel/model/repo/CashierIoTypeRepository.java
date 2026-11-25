package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.CashierIoType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CashierIoTypeRepository extends JpaRepository<CashierIoType, PrimaryKey> {

    List<CashierIoType> findByAccountSetsId(Long accountSetsId);

    Optional<CashierIoType> findFirstByAccountSetsIdAndNameAndType(
            Long accountSetsId, String name, CashierIoType.Type type);
}
