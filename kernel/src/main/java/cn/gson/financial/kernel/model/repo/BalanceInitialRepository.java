package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.BalanceInitial;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BalanceInitialRepository extends JpaRepository<BalanceInitial, PrimaryKey> {
}
