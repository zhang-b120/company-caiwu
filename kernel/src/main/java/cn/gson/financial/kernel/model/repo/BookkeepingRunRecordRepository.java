package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.BookkeepingRunRecord;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

public interface BookkeepingRunRecordRepository
        extends QuerydslPredicateExecutor<BookkeepingRunRecord>,
                JpaRepository<BookkeepingRunRecord, PrimaryKey> {

    /** GET /bookkeeping-records?accountSetsId=&period= 列表查询 */
    List<BookkeepingRunRecord> findByAccountSetsIdAndPeriodOrderByRunAtDesc(Long accountSetsId, String period);

    /** GET /bookkeeping-records/{runId} 详情查询 */
    Optional<BookkeepingRunRecord> findByRunId(String runId);
}
