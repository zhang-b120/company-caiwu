package cn.gson.financial.kernel.model.enums;

/**
 * FN-03 申报任务状态机
 * <p>
 * 合法转换：
 * <pre>
 *   PENDING   → PREPARING                       (claim 接口，乐观锁防并发)
 *   PREPARING → EXECUTING                       (start 接口)
 *   EXECUTING → SUCCESS / FAILED / MANUAL       (result 接口)
 * </pre>
 * SUCCESS / FAILED / MANUAL 为终态，不再 transition。
 */
public enum DeclareTaskStatus {

    /** 初始状态：刚创建，等待 worker 抢占 */
    PENDING,

    /** 已被某个 worker claim，正在准备申报数据 */
    PREPARING,

    /** 正在执行（与税局交互中） */
    EXECUTING,

    /** 终态：申报成功 */
    SUCCESS,

    /** 终态：申报失败（系统错误 / 税局拒收） */
    FAILED,

    /** 终态：转人工处理（验证码 / 异常等需人工介入） */
    MANUAL;

    /**
     * 是否终态。
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == MANUAL;
    }
}
