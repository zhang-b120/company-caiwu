package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.Assets;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;

public interface AssetsRepository extends QuerydslPredicateExecutor<Assets>, JpaRepository<Assets, PrimaryKey> {

    List<Assets> findByIdInAndAccountSetsId(List<Long> ids, Long accountSetsId);
}
