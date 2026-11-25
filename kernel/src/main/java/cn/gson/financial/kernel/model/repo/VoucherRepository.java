package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.Voucher;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

public interface VoucherRepository extends QuerydslPredicateExecutor<Voucher>, JpaRepository<Voucher, PrimaryKey> {
}
