package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.AccountSetProfile;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

public interface AccountSetProfileRepository
        extends QuerydslPredicateExecutor<AccountSetProfile>,
                JpaRepository<AccountSetProfile, PrimaryKey> {

    /** 返回该账套全部 section（启用 + 软删 都返，调用方可按 enabled 过滤）。 */
    List<AccountSetProfile> findByAccountSetsIdOrderByPriorityDescIdAsc(Long accountSetsId);

    Optional<AccountSetProfile> findByAccountSetsIdAndSectionTypeAndSectionKey(
            Long accountSetsId, String sectionType, String sectionKey);
}
