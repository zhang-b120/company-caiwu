package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.AssetsDepreciation;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;

public interface AssetsDepreciationRepository extends QuerydslPredicateExecutor<AssetsDepreciation>, JpaRepository<AssetsDepreciation, PrimaryKey> {

    List<AssetsDepreciation> findByVoucherIdIn(List<Long> voucherIds);
}
