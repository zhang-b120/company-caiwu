package cn.gson.financial.service;

import cn.gson.financial.kernel.model.entity.FlowBatch;
import cn.gson.financial.kernel.model.entity.FlowBatchItem;
import cn.gson.financial.kernel.model.repo.FlowBatchItemRepository;
import cn.gson.financial.kernel.model.repo.FlowBatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * FN-FLOW-BATCH-STATE：桌面批量三态持久化。
 * <p>建批次 + 批插 queued 明细；单户改 status/current_stage/note；看板拉最新批次或指定批次每户三态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowBatchService {

    private final FlowBatchRepository batchRepo;
    private final FlowBatchItemRepository itemRepo;

    private static final Set<String> END_STATES = Set.of("done", "failed");

    /** 建批次 + 批插 N 个 item(queued)。accountSetsIds 去重防 uk 撞键。 */
    @Transactional
    public FlowBatch createBatch(String period, String createdBy, String source, List<Long> accountSetsIds) {
        FlowBatch batch = new FlowBatch();
        batch.setPeriod(period);
        batch.setCreatedBy(createdBy);
        batch.setSource(source != null && !source.isBlank() ? source : "desktop");
        batch.setTotal(0);
        batch = batchRepo.save(batch);

        if (accountSetsIds != null && !accountSetsIds.isEmpty()) {
            List<FlowBatchItem> items = new ArrayList<>();
            for (Long accId : new LinkedHashSet<>(accountSetsIds)) {   // 去重
                if (accId == null) continue;
                FlowBatchItem it = new FlowBatchItem();
                it.setBatchId(batch.getId());
                it.setAccountSetId(accId);
                it.setPeriod(period);
                it.setStatus("queued");
                items.add(it);
            }
            itemRepo.saveAll(items);
            batch.setTotal(items.size());
            batch = batchRepo.save(batch);
        }
        log.info("[FN-FLOW-BATCH] 建批次 id={} period={} total={} source={}",
                batch.getId(), period, batch.getTotal(), batch.getSource());
        return batch;
    }

    /** 改单户三态/当前步骤/备注。status→running 补 started_at；→done/failed 补 ended_at。 */
    @Transactional
    public FlowBatchItem patchItem(Long batchId, Long accountSetId, String period,
                                   String status, String currentStage, String note) {
        // FN-FLOW-PATCH-UPSERT：找不到 (batchId,accountSetId,period) 不再 orElseThrow（补账历史月/目标月那行
        // 建批次时没插会被 BT 静默吞、补账户永远 queued）；改 upsert 新建一行（按三键），total 随新建累加。
        FlowBatchItem it = itemRepo.findByBatchIdAndAccountSetIdAndPeriod(batchId, accountSetId, period).orElse(null);
        boolean created = false;
        if (it == null) {
            it = new FlowBatchItem();
            it.setBatchId(batchId);
            it.setAccountSetId(accountSetId);
            it.setPeriod(period);
            it.setStatus("queued");   // 默认，下方按传入 status 覆盖
            created = true;
        }
        if (status != null && !status.isBlank()) {
            it.setStatus(status);
            if ("running".equals(status) && it.getStartedAt() == null) it.setStartedAt(LocalDateTime.now());
            if (END_STATES.contains(status)) it.setEndedAt(LocalDateTime.now());
        }
        if (currentStage != null) it.setCurrentStage(currentStage);
        if (note != null) it.setNote(note);
        FlowBatchItem saved = itemRepo.save(it);
        if (created) {
            // total 随新建明细累加（保持 batch.total 与实际明细行数一致）
            batchRepo.findById(batchId).ifPresent(b -> {
                b.setTotal((b.getTotal() == null ? 0 : b.getTotal()) + 1);
                batchRepo.save(b);
            });
            log.info("[FN-FLOW-PATCH-UPSERT] 批次 {} 补建明细 account={} period={} status={}",
                    batchId, accountSetId, period, it.getStatus());
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public FlowBatch latest(String period) {
        return batchRepo.findFirstByPeriodOrderByIdDesc(period).orElse(null);
    }

    @Transactional(readOnly = true)
    public FlowBatch findBatch(Long id) {
        return batchRepo.findById(id).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<FlowBatchItem> items(Long batchId) {
        return itemRepo.findByBatchIdOrderByIdAsc(batchId);
    }
}
