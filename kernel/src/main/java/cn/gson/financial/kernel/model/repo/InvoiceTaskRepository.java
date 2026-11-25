package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.InvoiceTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

/**
 * FN-04 开票任务 Repository
 */
public interface InvoiceTaskRepository
        extends JpaRepository<InvoiceTask, Long>, JpaSpecificationExecutor<InvoiceTask> {

    /**
     * 让 Spec 查询 + 分页一起复用 JpaSpecificationExecutor 默认实现。
     */
    @Override
    Page<InvoiceTask> findAll(org.springframework.data.jpa.domain.Specification<InvoiceTask> spec, Pageable pageable);

    /**
     * FN-INV-T2/T3（132）：按订单号查任务（扫码直开链路 + 收银端查进度）。
     * uk_task_order_no UNIQUE 保证最多一条。
     */
    Optional<InvoiceTask> findByOrderNo(String orderNo);
}
