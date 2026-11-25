package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.VoucherDetails;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;

public interface VoucherDetailsRepository extends QuerydslPredicateExecutor<VoucherDetails>, JpaRepository<VoucherDetails, PrimaryKey> {

    List<VoucherDetails> findByVoucherIdIn(List<Long> voucherIds);
}
