package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.AssetsChange;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;

public interface AssetsChangeRepository extends QuerydslPredicateExecutor<AssetsChange>, JpaRepository<AssetsChange, PrimaryKey> {

    List<AssetsChange> findByVoucherIdIn(List<Long> voucherIds);
}
