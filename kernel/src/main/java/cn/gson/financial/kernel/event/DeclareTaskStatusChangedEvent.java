package cn.gson.financial.kernel.event;

import cn.gson.financial.kernel.model.entity.DeclareTask;
import cn.gson.financial.kernel.model.enums.DeclareTaskStatus;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * FN-05 申报任务状态变更事件
 * <p>
 * 由 {@link cn.gson.financial.kernel.service.DeclareTaskService} 在 claim / start /
 * completeWithResult 等状态机转换成功后 publishEvent。
 * <p>
 * 异步监听者（@Async）：
 *   - wx 推送（任务领取/失败时通知客户）
 *   - 企微推送（执行结果通知会计）
 *   - finmaster 工单（FAILED/MANUAL 终态时自动建工单跟进）
 *
 * 复用 source = task.getId() 便于 Spring 把 source 关联到任务实体。
 */
@Getter
public class DeclareTaskStatusChangedEvent extends ApplicationEvent {

    /** 任务 ID（同 source） */
    private final Long taskId;
    private final Long accountSetsId;
    private final Long customerId;
    private final String taxType;
    private final Integer periodYear;
    private final Integer periodMonth;
    private final Integer periodQuarter;

    /** 状态机迁移：null 表示新建（PENDING） */
    private final DeclareTaskStatus fromStatus;
    private final DeclareTaskStatus toStatus;

    /** 当前 worker 标识（claim 时填入，后续保留） */
    private final String executor;

    /** 失败/MANUAL 终态时携带，便于下游推送原因 */
    private final String errorMsg;

    public DeclareTaskStatusChangedEvent(DeclareTask task,
                                         DeclareTaskStatus fromStatus,
                                         DeclareTaskStatus toStatus) {
        super(task.getId());
        this.taskId = task.getId();
        this.accountSetsId = task.getAccountSetsId();
        this.customerId = task.getCustomerId();
        this.taxType = task.getTaxType();
        this.periodYear = task.getPeriodYear();
        this.periodMonth = task.getPeriodMonth();
        this.periodQuarter = task.getPeriodQuarter();
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.executor = task.getExecutor();
        this.errorMsg = task.getErrorMsg();
    }

    /** 是否进入终态（SUCCESS / FAILED / MANUAL） */
    public boolean isTerminal() {
        return toStatus == DeclareTaskStatus.SUCCESS
                || toStatus == DeclareTaskStatus.FAILED
                || toStatus == DeclareTaskStatus.MANUAL;
    }
}
