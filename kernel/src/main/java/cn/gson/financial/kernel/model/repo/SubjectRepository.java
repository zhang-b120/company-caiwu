package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;

public interface SubjectRepository extends QuerydslPredicateExecutor<Subject>, JpaRepository<Subject, PrimaryKey> {
    @Query("select s from Subject s where s.accountSetsId = ?1")
    List<Subject> selectByAccountSetsId(Long accountSetsId);
}
