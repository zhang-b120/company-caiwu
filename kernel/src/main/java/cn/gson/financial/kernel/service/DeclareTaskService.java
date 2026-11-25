package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.event.DeclareTaskStatusChangedEvent;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.entity.DeclareTask;
import cn.gson.financial.kernel.model.enums.DeclareTaskStatus;
import cn.gson.financial.kernel.model.repo.DeclareTaskRepository;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

/**
 * FN-03 申报任务 Service
 * <p>
 * 包含状态机校验 + 乐观锁 claim。所有 setStatus 都必须经由 {@link #transitionTo} 校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeclareTaskService {

    /** ServiceException 消息中携带的"业务错误码"前缀，controller 取出后塞进 errorType 字段。 */
    public static final String ERR_PREFIX = "[errorType=";

    /** 业务错误码（写到 ServiceException.message 头部，controller 通过 parseErrorType 提取） */
    public static final String ERROR_INVALID_TRANSITION = "INVALID_TRANSITION";
    public static final String ERROR_ALREADY_CLAIMED = "ALREADY_CLAIMED";
    public static final String ERROR_NOT_FOUND = "NOT_FOUND";
    public static final String ERROR_INVALID_STATUS = "INVALID_STATUS";

    /** 合法状态转换表（不可变）。终态不在 keySet 中，等价于不可再 transition。 */
    private static final Map<DeclareTaskStatus, Set<DeclareTaskStatus>> VALID_TRANSITIONS;

    static {
        Map<DeclareTaskStatus, Set<DeclareTaskStatus>> m = new EnumMap<>(DeclareTaskStatus.class);
        m.put(DeclareTaskStatus.PENDING, Collections.singleton(DeclareTaskStatus.PREPARING));
        m.put(DeclareTaskStatus.PREPARING, Collections.singleton(DeclareTaskStatus.EXECUTING));
        Set<DeclareTaskStatus> exec = EnumSet.of(
                DeclareTaskStatus.SUCCESS, DeclareTaskStatus.FAILED, DeclareTaskStatus.MANUAL);
        m.put(DeclareTaskStatus.EXECUTING, Collections.unmodifiableSet(exec));
        VALID_TRANSITIONS = Collections.unmodifiableMap(m);
    }

    private final DeclareTaskRepository declareTaskRepository;

    /**
     * FN-05 事件总线：状态变更后 publishEvent，由异步监听器（@Async）做下游推送，
     * 不阻塞 service 返回。injection 用 @RequiredArgsConstructor 注入 ApplicationEventPublisher。
     */
    private final ApplicationEventPublisher eventPublisher;

    // ═══════════════════════════════════════════════════════════════
    // 查询
    // ═══════════════════════════════════════════════════════════════

    public Optional<DeclareTask> findById(Long id) {
        return declareTaskRepository.findById(id);
    }

    /**
     * 列表分页查询（按 status / customerId / accountSetsId 任意组合过滤）。
     * 默认按 createdAt desc。
     */
    public Page<DeclareTask> page(DeclareTaskStatus status,
                                  Long customerId,
                                  Long accountSetsId,
                                  int page,
                                  int size) {
        if (page < 1) page = 1;
        if (size < 1) size = 50;
        if (size > 500) size = 500;
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<DeclareTask> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> ps = new ArrayList<>();
            if (status != null) {
                ps.add(cb.equal(root.get("status"), status));
            }
            if (customerId != null) {
                ps.add(cb.equal(root.get("customerId"), customerId));
            }
            if (accountSetsId != null) {
                ps.add(cb.equal(root.get("accountSetsId"), accountSetsId));
            }
            return ps.isEmpty() ? cb.conjunction() : cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        return declareTaskRepository.findAll(spec, pageable);
    }

    // ═══════════════════════════════════════════════════════════════
    // 创建 / 状态转换
    // ═══════════════════════════════════════════════════════════════

    /**
     * 单条保存（默认事务）。新建时 status 默认 PENDING（entity 默认值）。
     */
    @Transactional
    public DeclareTask save(DeclareTask task) {
        validateForCreate(task);
        if (task.getStatus() == null) {
            task.setStatus(DeclareTaskStatus.PENDING);
        }
        if (task.getDryRun() == null) {
            task.setDryRun(Boolean.FALSE);
        }
        return declareTaskRepository.save(task);
    }

    /**
     * 独立事务保存（批量场景下一条失败不影响其他）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public DeclareTask saveInNewTransaction(DeclareTask task) {
        return save(task);
    }

    /**
     * 抢占任务：PENDING → PREPARING（乐观锁）。
     * <p>
     * 两个 worker 同时 claim 同一任务时，由 @Version 字段保证 update 只命中一行。
     * 后到的 worker 拿到 OptimisticLockException → 转 ALREADY_CLAIMED。
     */
    @Transactional
    public DeclareTask claim(Long id, String executor) {
        if (!StringUtils.hasText(executor)) {
            throw bizError(ERROR_INVALID_STATUS, "executor 不能为空");
        }
        DeclareTask task = declareTaskRepository.findById(id)
                .orElseThrow(() -> bizError(ERROR_NOT_FOUND, "申报任务不存在: id=" + id));

        if (task.getStatus() != DeclareTaskStatus.PENDING) {
            // 已被抢走或已是其他状态：直接返回 ALREADY_CLAIMED 让调用方知道
            throw bizError(ERROR_ALREADY_CLAIMED,
                    "任务已被抢占或非 PENDING：当前 status=" + task.getStatus()
                            + (task.getExecutor() != null ? ", executor=" + task.getExecutor() : ""));
        }

        try {
            transitionTo(task, DeclareTaskStatus.PREPARING);
            task.setExecutor(executor.trim());
            // saveAndFlush 把 update 立即推给 DB，让 OptimisticLockException 当场抛出
            DeclareTask saved = declareTaskRepository.saveAndFlush(task);
            // FN-05 发事件：PENDING → PREPARING
            eventPublisher.publishEvent(new DeclareTaskStatusChangedEvent(
                    saved, DeclareTaskStatus.PENDING, DeclareTaskStatus.PREPARING));
            return saved;
        } catch (OptimisticLockException | OptimisticLockingFailureException e) {
            log.info("claim 乐观锁冲突: id={}, executor={}", id, executor);
            throw bizError(ERROR_ALREADY_CLAIMED, "任务已被其他 worker 抢占（乐观锁冲突）");
        }
    }

    /**
     * 开始执行：PREPARING → EXECUTING。
     */
    @Transactional
    public DeclareTask start(Long id) {
        DeclareTask task = declareTaskRepository.findById(id)
                .orElseThrow(() -> bizError(ERROR_NOT_FOUND, "申报任务不存在: id=" + id));
        DeclareTaskStatus from = task.getStatus();
        transitionTo(task, DeclareTaskStatus.EXECUTING);
        DeclareTask saved = declareTaskRepository.save(task);
        // FN-05 发事件：PREPARING → EXECUTING
        eventPublisher.publishEvent(new DeclareTaskStatusChangedEvent(
                saved, from, DeclareTaskStatus.EXECUTING));
        return saved;
    }

    /**
     * 提交结果：EXECUTING → SUCCESS / FAILED / MANUAL。
     * <p>
     * resultJson / errorMsg 均可选。submittedAt 自动写为 now。
     */
    @Transactional
    public DeclareTask completeWithResult(Long id,
                                          DeclareTaskStatus targetStatus,
                                          String resultJson,
                                          String errorMsg) {
        if (targetStatus == null
                || (targetStatus != DeclareTaskStatus.SUCCESS
                && targetStatus != DeclareTaskStatus.FAILED
                && targetStatus != DeclareTaskStatus.MANUAL)) {
            throw bizError(ERROR_INVALID_STATUS,
                    "result 接口只能转 SUCCESS / FAILED / MANUAL，当前: " + targetStatus);
        }
        DeclareTask task = declareTaskRepository.findById(id)
                .orElseThrow(() -> bizError(ERROR_NOT_FOUND, "申报任务不存在: id=" + id));
        DeclareTaskStatus from = task.getStatus();
        transitionTo(task, targetStatus);
        if (resultJson != null) task.setResultJson(resultJson);
        if (errorMsg != null) task.setErrorMsg(errorMsg);
        task.setSubmittedAt(LocalDateTime.now());
        DeclareTask saved = declareTaskRepository.save(task);
        // FN-05 发事件：EXECUTING → SUCCESS/FAILED/MANUAL（终态）
        eventPublisher.publishEvent(new DeclareTaskStatusChangedEvent(saved, from, targetStatus));
        return saved;
    }

    // ═══════════════════════════════════════════════════════════════
    // 状态机核心：所有 setStatus 必须走这里
    // ═══════════════════════════════════════════════════════════════

    /**
     * 检查当前 → 目标 状态是否合法转换。非法直接抛 ServiceException(INVALID_TRANSITION)。
     */
    public void transitionTo(DeclareTask task, DeclareTaskStatus target) {
        DeclareTaskStatus current = task.getStatus();
        if (current == null) {
            throw bizError(ERROR_INVALID_TRANSITION, "任务当前状态为 null，无法转换");
        }
        if (current == target) {
            // 幂等：相同状态不算非法，但也不写
            return;
        }
        Set<DeclareTaskStatus> allowed = VALID_TRANSITIONS.get(current);
        if (allowed == null || !allowed.contains(target)) {
            throw bizError(ERROR_INVALID_TRANSITION,
                    "非法状态转换: " + current + " → " + target
                            + "（允许的转换: " + (allowed == null ? "无（终态）" : allowed) + "）");
        }
        task.setStatus(target);
    }

    /**
     * 给外部测试 / 调用方查询合法转换表。
     */
    public static Set<DeclareTaskStatus> allowedTransitions(DeclareTaskStatus from) {
        Set<DeclareTaskStatus> s = VALID_TRANSITIONS.get(from);
        return s == null ? Collections.emptySet() : s;
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════════

    private static void validateForCreate(DeclareTask task) {
        if (task == null) throw bizError(ERROR_INVALID_STATUS, "task 不能为空");
        if (task.getAccountSetsId() == null) throw bizError(ERROR_INVALID_STATUS, "accountSetsId 不能为空");
        if (task.getCustomerId() == null) throw bizError(ERROR_INVALID_STATUS, "customerId 不能为空");
        if (!StringUtils.hasText(task.getTaxType())) throw bizError(ERROR_INVALID_STATUS, "taxType 不能为空");
        if (task.getPeriodYear() == null) throw bizError(ERROR_INVALID_STATUS, "periodYear 不能为空");
        if (task.getPeriodMonth() == null && task.getPeriodQuarter() == null) {
            throw bizError(ERROR_INVALID_STATUS, "periodMonth 与 periodQuarter 必须至少有一个");
        }
    }

    /**
     * 构造一个携带 errorType 前缀的 ServiceException，
     * controller 端用 {@link #extractErrorType} 解析。
     */
    public static ServiceException bizError(String errorType, String msg) {
        return new ServiceException(ERR_PREFIX + errorType + "] " + msg);
    }

    /**
     * 从 ServiceException 的 message 头部提取 errorType；没有则返回 null。
     */
    public static String extractErrorType(String message) {
        if (message == null || !message.startsWith(ERR_PREFIX)) return null;
        int end = message.indexOf(']');
        if (end <= ERR_PREFIX.length()) return null;
        return message.substring(ERR_PREFIX.length(), end);
    }

    /**
     * 把 ServiceException 的 message 去掉 errorType 前缀后返回纯文案。
     */
    public static String stripErrorType(String message) {
        if (message == null) return null;
        if (!message.startsWith(ERR_PREFIX)) return message;
        int end = message.indexOf(']');
        if (end < 0) return message;
        // 去掉 "[errorType=XXX] " 前缀（包括尾部空格）
        String tail = message.substring(end + 1);
        return tail.startsWith(" ") ? tail.substring(1) : tail;
    }
}
