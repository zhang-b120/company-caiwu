package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.UserAccountSets;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;

public interface UserAccountSetsRepository extends JpaRepository<UserAccountSets, PrimaryKey>, QuerydslPredicateExecutor<UserAccountSets> {
    @Query("select count(u) from UserAccountSets u where u.accountSetsId = ?1 and u.userId = ?2")
    long selectCount(Long accountSetsId, Long userId);

    @Query("select u from UserAccountSets u where u.accountSetsId = ?1 and u.userId = ?2")
    List<UserAccountSets> selectList(Long accountSetsId, Long userId);

    @Query("select u.accountSetsId from UserAccountSets u where u.userId = ?1")
    List<Long> findAccountSetIdsByUserId(Long userId);
}
