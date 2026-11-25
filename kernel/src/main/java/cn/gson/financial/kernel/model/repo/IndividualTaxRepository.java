package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.IndividualTax;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface IndividualTaxRepository extends JpaRepository<IndividualTax, PrimaryKey>{
    /**
     * 根据账套ID列表和账期删除记录
     */
    void deleteByAccountSetsIdInAndPeriod(List<Long> accountSetsIds, LocalDate period);

    /**
     * 根据账套ID列表和账期查询记录
     */
    List<IndividualTax> findByAccountSetsIdInAndPeriod(List<Long> accountSetsIds, LocalDate period);

    /**
     * 根据账套ID删除所有账期的记录
     */
    void deleteByAccountSetsId(Long accountSetsId);
} 