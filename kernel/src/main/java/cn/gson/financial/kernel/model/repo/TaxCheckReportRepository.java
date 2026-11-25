package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaxCheckReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * FN-NEW-02 税务检查报告 Repository
 */
public interface TaxCheckReportRepository extends JpaRepository<TaxCheckReport, Long> {

    Page<TaxCheckReport> findByCustomerIdOrderByCreatedAtDesc(Long customerId, Pageable pageable);
}
