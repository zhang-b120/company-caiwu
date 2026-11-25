package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.Checkout;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.Optional;

public interface CheckoutRepository extends QuerydslPredicateExecutor<Checkout>, JpaRepository<Checkout, PrimaryKey> {

    boolean existsByAccountSetsIdAndCheckYearAndCheckMonthAndStatus(Long accountSetsId, Integer checkYear, Integer checkMonth, Integer status);

    Optional<Checkout> findByAccountSetsIdAndCheckYearAndCheckMonth(Long accountSetsId, Integer checkYear, Integer checkMonth);
}
