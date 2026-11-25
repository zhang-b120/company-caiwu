package cn.gson.financial.task.workbench.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 步骤完成事件监听器。
 *
 * <p>在当前事务 <b>提交成功后</b>，异步触发后置步骤的自动下发。
 * 这样回调事务（写发票下载状态）与做账/申报事务完全隔离：
 * <ul>
 *   <li>回调事务提交不受做账失败影响</li>
 *   <li>做账/申报运行在独立事务里，失败只影响自身</li>
 *   <li>不阻塞 OC 回调的 HTTP 响应</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StepCompletedEventListener {

    private final TaskWorkbenchService taskWorkbenchService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStepCompleted(StepCompletedEvent event) {
        String step = event.getCompletedStep();
        log.info("步骤完成，异步触发后置任务: accountSetId={}, period={}, completedStep={}",
                event.getAccountSetId(), event.getPeriod(), step);
        try {
            if ("data_local".equals(step)) {
                // OC 发票任务全部完成，异步执行本地子任务（bank/salary）
                taskWorkbenchService.autoExecutePendingLocalDataSubs(
                        event.getAccountSetId(), event.getPeriod());
            } else {
                taskWorkbenchService.autoDispatchBlockedDependents(
                        event.getAccountSetId(), event.getPeriod(), step);
            }
        } catch (Exception e) {
            log.error("后置任务触发失败: accountSetId={}, period={}, completedStep={}",
                    event.getAccountSetId(), event.getPeriod(), step, e);
        }
    }
}
