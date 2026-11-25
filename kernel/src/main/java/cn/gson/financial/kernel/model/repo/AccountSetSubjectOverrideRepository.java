package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.AccountSetSubjectOverride;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

public interface AccountSetSubjectOverrideRepository
        extends QuerydslPredicateExecutor<AccountSetSubjectOverride>,
                JpaRepository<AccountSetSubjectOverride, PrimaryKey> {

    List<AccountSetSubjectOverride> findByAccountSetsId(Long accountSetsId);

    Optional<AccountSetSubjectOverride> findByAccountSetsIdAndBizMeaning(Long accountSetsId, String bizMeaning);

    long deleteByAccountSetsIdAndBizMeaning(Long accountSetsId, String bizMeaning);
}
