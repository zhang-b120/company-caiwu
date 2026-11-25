package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.Stocks;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StocksRepository extends JpaRepository<Stocks, PrimaryKey> {
}
