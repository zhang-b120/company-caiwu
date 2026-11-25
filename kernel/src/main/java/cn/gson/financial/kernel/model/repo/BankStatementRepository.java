package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.BankStatement;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.stereotype.Repository;

public interface BankStatementRepository extends JpaRepository<BankStatement, PrimaryKey>, QuerydslPredicateExecutor<BankStatement> {

    java.util.List<BankStatement> findByAccountSetsIdAndPeriodAndIsDeleteOrderByCreateTimeDesc(
            Long accountSetsId, String period, int isDelete);

    java.util.Optional<BankStatement> findFirstByIdAndAccountSetsIdAndIsDelete(
            Long id, Long accountSetsId, int isDelete);
}
