package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.AccountingCategory;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

public interface AccountingCategoryRepository extends QuerydslPredicateExecutor<AccountingCategory>, JpaRepository<AccountingCategory, PrimaryKey> {
}
