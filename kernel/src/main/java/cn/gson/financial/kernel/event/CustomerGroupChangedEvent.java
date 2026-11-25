package cn.gson.financial.kernel.event;

import cn.gson.financial.kernel.model.entity.CustomerGroup;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * FN-05 客户群路由变更事件（FN-09 customer_group 表的写操作）
 * <p>
 * <b>用途</b>：触发 WC（企微客服服务）失效本地缓存。WC-08 把"客户群 → 4 个员工"的
 * 路由表（6000 群 × 4 人）加载到本地内存做高频路由，financial 端任何 group / employee
 * 变更都必须立刻让 WC 失效缓存，否则路由会发到错的员工。
 * <p>
 * 发布点：
 *   - CREATE：新建一条 customer_group（含 batch / Excel 导入每条）
 *   - UPDATE_EMPLOYEES：调整 4 个员工字段
 *   - DELETE：删除一条 customer_group（暂未实现 delete API，预留 enum 值）
 * <p>
 * 监听者：{@link cn.gson.financial.event.TaskNotificationListener#onCustomerGroupChanged}
 * 异步 push 到 {@code app.notify.wecom-push-url} 通知 WC 缓存失效。
 */
@Getter
public class CustomerGroupChangedEvent extends ApplicationEvent {

    public enum ChangeType {
        CREATE, UPDATE_EMPLOYEES, DELETE
    }

    private final Long customerGroupId;
    private final String wecomChatId;
    private final Long customerId;
    private final ChangeType changeType;

    /** 调整后/新建后的员工字段（DELETE 时全 null） */
    private final Long salesEmployeeId;
    private final Long csEmployeeId;
    private final Long accountantEmployeeId;
    private final Long govEmployeeId;

    public CustomerGroupChangedEvent(CustomerGroup group, ChangeType changeType) {
        super(group.getId());
        this.customerGroupId = group.getId();
        this.wecomChatId = group.getWecomChatId();
        this.customerId = group.getCustomerId();
        this.changeType = changeType;
        this.salesEmployeeId = group.getSalesEmployeeId();
        this.csEmployeeId = group.getCsEmployeeId();
        this.accountantEmployeeId = group.getAccountantEmployeeId();
        this.govEmployeeId = group.getGovEmployeeId();
    }
}
