package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.VoucherReasoningRecord;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;

public interface VoucherReasoningRecordRepository
        extends QuerydslPredicateExecutor<VoucherReasoningRecord>,
                JpaRepository<VoucherReasoningRecord, PrimaryKey> {

    /** 按 run_id 拉一次做账的全部凭证推理（详情接口用） */
    List<VoucherReasoningRecord> findByRunIdOrderByCreatedAtAsc(String runId);
}
