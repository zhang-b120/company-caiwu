package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

public interface UserRepository extends QuerydslPredicateExecutor<User>, JpaRepository<User, Long> {

    @Query("select u from User u where u.mobile = ?1")
    User findByMobile(String mobile);
}
