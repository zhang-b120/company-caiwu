package cn.gson.financial.service;

import cn.gson.financial.kernel.model.entity.FlowAutopilot;
import cn.gson.financial.kernel.model.repo.FlowAutopilotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * FN-AUTOPILOT-SWITCH：无人值守「自动做账+报税」开关。
 * <p>生效自动 = <b>全局总开关 ON</b> 且 <b>该账套 ON</b>（白名单 opt-in）。
 * 全局缺省 ON（无 account_set_id=0 行视为开）；账套缺省 OFF（不在白名单不自动）。
 * <p>只有生效自动的账套才进 full-flow 队列（FN-QUEUE-FULLFLOW）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutopilotService {

    public static final long GLOBAL_ID = 0L;

    private final FlowAutopilotRepository repo;

    /** 全局总开关：无行=默认 ON（避免误关全部）。 */
    @Transactional(readOnly = true)
    public boolean globalEnabled() {
        return repo.findByAccountSetId(GLOBAL_ID).map(a -> Boolean.TRUE.equals(a.getEnabled())).orElse(true);
    }

    /** 账套级开关：无行=默认 OFF（白名单 opt-in）。 */
    @Transactional(readOnly = true)
    public boolean accountEnabled(Long accountSetId) {
        if (accountSetId == null) return false;
        return repo.findByAccountSetId(accountSetId).map(a -> Boolean.TRUE.equals(a.getEnabled())).orElse(false);
    }

    /** 该账套是否「生效自动」= 全局 ON 且 账套 ON。 */
    @Transactional(readOnly = true)
    public boolean isAutopilot(Long accountSetId) {
        return globalEnabled() && accountEnabled(accountSetId);
    }

    /** 生效自动的账套 id 列表（全局 OFF → 空）。供 full-flow 队列选取。 */
    @Transactional(readOnly = true)
    public List<Long> autopilotAccountIds() {
        if (!globalEnabled()) return List.of();
        return repo.findByEnabledTrue().stream()
                .map(FlowAutopilot::getAccountSetId)
                .filter(id -> id != null && id != GLOBAL_ID)
                .collect(Collectors.toList());
    }

    /** 设账套开关（upsert）。 */
    @Transactional
    public FlowAutopilot setAccount(Long accountSetId, boolean enabled, String operator) {
        return upsert(accountSetId, enabled, operator);
    }

    /** 设全局总开关（upsert，account_set_id=0）。 */
    @Transactional
    public FlowAutopilot setGlobal(boolean enabled, String operator) {
        return upsert(GLOBAL_ID, enabled, operator);
    }

    private FlowAutopilot upsert(Long accountSetId, boolean enabled, String operator) {
        FlowAutopilot row = repo.findByAccountSetId(accountSetId).orElseGet(() -> {
            FlowAutopilot a = new FlowAutopilot();
            a.setAccountSetId(accountSetId);
            return a;
        });
        row.setEnabled(enabled);
        row.setUpdatedBy(operator);
        try {
            return repo.save(row);
        } catch (DataIntegrityViolationException dup) {
            // 并发首建撞 UNIQUE → 回查再写
            FlowAutopilot existing = repo.findByAccountSetId(accountSetId).orElseThrow(() -> dup);
            existing.setEnabled(enabled);
            existing.setUpdatedBy(operator);
            return repo.save(existing);
        }
    }
}
