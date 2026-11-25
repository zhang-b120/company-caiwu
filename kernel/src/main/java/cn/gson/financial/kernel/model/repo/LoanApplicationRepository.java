package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.LoanApplication;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

/**
 * FN-NEW-01 贷款申请 Repository
 */
public interface LoanApplicationRepository
        extends JpaRepository<LoanApplication, Long>, JpaSpecificationExecutor<LoanApplication> {

    List<LoanApplication> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    Page<LoanApplication> findByCustomerIdOrderByCreatedAtDesc(Long customerId, Pageable pageable);

    @Override
    Page<LoanApplication> findAll(Specification<LoanApplication> spec, Pageable pageable);
}
