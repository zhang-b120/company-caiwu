package cn.gson.financial.kernel.model.repo;


import cn.gson.financial.kernel.model.entity.Salary;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;


public interface SalaryRepository extends JpaRepository<Salary, PrimaryKey> {

    /**
     * 查询指定账套、指定年月的工资记录
     */
    List<Salary> findByAccountSetsIdAndYearAndMonth(Long accountSetsId, Integer year, Integer month);

    /**
     * 批量查出指定年月中存在工资记录（已复核）的账套ID集合
     */
    @Query("SELECT DISTINCT s.accountSetsId FROM Salary s WHERE s.year = :year AND s.month = :month AND s.accountSetsId IN :accountSetIds AND s.state = true")
    Set<Long> findAccountSetIdsWithReviewedSalary(@Param("year") int year, @Param("month") int month, @Param("accountSetIds") List<Long> accountSetIds);
}
