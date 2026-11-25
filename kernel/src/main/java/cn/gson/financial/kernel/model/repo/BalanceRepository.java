package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.Balance;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BalanceRepository extends JpaRepository<Balance, PrimaryKey> {
}
