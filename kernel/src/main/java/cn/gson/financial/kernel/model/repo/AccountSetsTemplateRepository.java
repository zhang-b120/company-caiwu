package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.AccountSetsTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

public interface AccountSetsTemplateRepository extends QuerydslPredicateExecutor<AccountSetsTemplate>, JpaRepository<AccountSetsTemplate, Long> {
}
