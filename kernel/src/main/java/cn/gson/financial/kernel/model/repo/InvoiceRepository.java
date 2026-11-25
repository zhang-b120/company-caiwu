package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.Invoice;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface InvoiceRepository extends JpaRepository<Invoice, PrimaryKey> {

    List<Invoice> findByVoucherIdIn(List<Long> voucherIds);


    /**
     * 批量按账套+日期范围聚合发票统计：张数、金额、税额，按 accountSetsId + type + showStatus 分组。
     * 期间过滤：assignmentDate 优先，为空时 fallback 到 tradeDate（与做账逻辑一致）。
     * 排除作废发票（state=0 即"正常"）。
     * 返回 Object[]：[accountSetsId, type, showStatus, count, sumAmount, sumTaxAmount]
     */
    @Query("SELECT i.accountSetsId, i.type, i.showStatus, COUNT(i), COALESCE(SUM(i.amount),0), COALESCE(SUM(i.taxAmount),0) " +
           "FROM Invoice i " +
           "WHERE i.accountSetsId IN :ids " +
           "AND ((i.assignmentDate >= :start AND i.assignmentDate < :end) " +
           "     OR (i.assignmentDate IS NULL AND i.tradeDate >= :start AND i.tradeDate < :end)) " +
           "AND (i.state IS NULL OR i.state = 0) " +
           "GROUP BY i.accountSetsId, i.type, i.showStatus")
    List<Object[]> summarizeByAccountSetIdsAndDateRange(
            @Param("ids") List<Long> accountSetIds,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);
}
