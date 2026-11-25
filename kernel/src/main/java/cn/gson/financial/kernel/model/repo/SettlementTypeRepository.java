package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.SettlementType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementTypeRepository extends JpaRepository<SettlementType, PrimaryKey> {
}
