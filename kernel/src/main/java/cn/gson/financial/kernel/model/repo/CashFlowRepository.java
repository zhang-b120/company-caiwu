package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.CashFlow;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashFlowRepository extends JpaRepository<CashFlow, PrimaryKey> {
}
