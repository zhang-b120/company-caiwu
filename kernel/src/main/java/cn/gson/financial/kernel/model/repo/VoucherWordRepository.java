package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.VoucherWord;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

public interface VoucherWordRepository extends QuerydslPredicateExecutor<VoucherWord>, JpaRepository<VoucherWord, PrimaryKey> {

    java.util.Optional<VoucherWord> findFirstByAccountSetsIdAndIsDefault(Long accountSetsId, Boolean isDefault);
}
