package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.event.CustomerGroupChangedEvent;
import cn.gson.financial.kernel.event.CustomerGroupChangedEvent.ChangeType;
import cn.gson.financial.kernel.model.entity.CustomerGroup;
import cn.gson.financial.kernel.model.repo.CustomerGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * FN-09 客户群路由 Service
 */
@Service
@RequiredArgsConstructor
public class CustomerGroupService {

    private final CustomerGroupRepository customerGroupRepository;

    /**
     * FN-05 事件总线：customer_group 变更后 publishEvent，
     * 由 {@link cn.gson.financial.event.TaskNotificationListener#onCustomerGroupChanged}
     * 异步推到 WC 触发缓存失效（WC-08 关键路径）。
     */
    private final ApplicationEventPublisher eventPublisher;

    public Optional<CustomerGroup> findByWecomChatId(String wecomChatId) {
        return customerGroupRepository.findByWecomChatId(wecomChatId);
    }

    public Optional<CustomerGroup> findById(Long id) {
        return customerGroupRepository.findById(id);
    }

    public List<CustomerGroup> findByEmployeeId(Long employeeId) {
        // 在 4 个角色字段中任意命中即返回；用 4 次查询合并，避免 OR 导致索引失效
        java.util.LinkedHashMap<Long, CustomerGroup> merged = new java.util.LinkedHashMap<>();
        for (CustomerGroup g : customerGroupRepository.findBySalesEmployeeId(employeeId)) {
            merged.put(g.getId(), g);
        }
        for (CustomerGroup g : customerGroupRepository.findByCsEmployeeId(employeeId)) {
            merged.putIfAbsent(g.getId(), g);
        }
        for (CustomerGroup g : customerGroupRepository.findByAccountantEmployeeId(employeeId)) {
            merged.putIfAbsent(g.getId(), g);
        }
        for (CustomerGroup g : customerGroupRepository.findByGovEmployeeId(employeeId)) {
            merged.putIfAbsent(g.getId(), g);
        }
        return new java.util.ArrayList<>(merged.values());
    }

    @Transactional
    public CustomerGroup save(CustomerGroup g) {
        boolean isCreate = (g.getId() == null);
        CustomerGroup saved = customerGroupRepository.save(g);
        // FN-05 发事件：CREATE / UPDATE — 都让 WC 失效缓存（保守起见 update 也发）
        eventPublisher.publishEvent(new CustomerGroupChangedEvent(saved,
                isCreate ? ChangeType.CREATE : ChangeType.UPDATE_EMPLOYEES));
        return saved;
    }

    /**
     * 独立事务保存（批量场景下一条失败不影响其他）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public CustomerGroup saveInNewTransaction(CustomerGroup g) {
        // 复用 save() 走同一条事件发布路径，避免批量导入时 WC 漏失效
        return save(g);
    }

    @Transactional
    public CustomerGroup updateEmployees(Long id,
                                         Long salesEmployeeId,
                                         Long csEmployeeId,
                                         Long accountantEmployeeId,
                                         Long govEmployeeId) {
        CustomerGroup g = customerGroupRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("customer_group 不存在: " + id));
        g.setSalesEmployeeId(salesEmployeeId);
        g.setCsEmployeeId(csEmployeeId);
        g.setAccountantEmployeeId(accountantEmployeeId);
        g.setGovEmployeeId(govEmployeeId);
        CustomerGroup saved = customerGroupRepository.save(g);
        // FN-05 发事件：员工调整必发 — WC 本地路由表必须立即换 4 员工映射
        eventPublisher.publishEvent(new CustomerGroupChangedEvent(saved, ChangeType.UPDATE_EMPLOYEES));
        return saved;
    }

    public boolean existsByWecomChatId(String wecomChatId) {
        return customerGroupRepository.existsByWecomChatId(wecomChatId);
    }
}
