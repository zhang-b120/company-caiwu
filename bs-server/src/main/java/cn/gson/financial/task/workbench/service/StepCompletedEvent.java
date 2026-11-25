package cn.gson.financial.task.workbench.service;

/**
 * 步骤完成事件。
 * 由 @TransactionalEventListener(AFTER_COMMIT) 监听，
 * 保证当前事务提交后再异步触发后置步骤的下发，彻底隔离回调事务与做账/申报事务。
 */
public class StepCompletedEvent {

    private final Long accountSetId;
    private final String period;
    private final String completedStep;

    public StepCompletedEvent(Long accountSetId, String period, String completedStep) {
        this.accountSetId = accountSetId;
        this.period = period;
        this.completedStep = completedStep;
    }

    public Long getAccountSetId() { return accountSetId; }
    public String getPeriod()     { return period; }
    public String getCompletedStep() { return completedStep; }
}
