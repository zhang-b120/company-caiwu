package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.VoucherAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * FN-NEW-AUDIT-LOG 凭证审核日志 Repository
 */
public interface VoucherAuditLogRepository
        extends JpaRepository<VoucherAuditLog, Long>, JpaSpecificationExecutor<VoucherAuditLog> {

    @Override
    Page<VoucherAuditLog> findAll(Specification<VoucherAuditLog> spec, Pageable pageable);
}
