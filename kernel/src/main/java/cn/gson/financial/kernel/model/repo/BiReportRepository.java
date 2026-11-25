package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.BiReport;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

public interface BiReportRepository extends QuerydslPredicateExecutor<BiReport>, JpaRepository<BiReport, PrimaryKey> {
}
