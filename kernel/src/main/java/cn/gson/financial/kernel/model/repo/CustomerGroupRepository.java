package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.CustomerGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * FN-09 客户群路由 Repository
 */
public interface CustomerGroupRepository extends JpaRepository<CustomerGroup, Long> {

    Optional<CustomerGroup> findByWecomChatId(String wecomChatId);

    boolean existsByWecomChatId(String wecomChatId);

    List<CustomerGroup> findByCustomerId(Long customerId);

    List<CustomerGroup> findBySalesEmployeeId(Long employeeId);

    List<CustomerGroup> findByCsEmployeeId(Long employeeId);

    List<CustomerGroup> findByAccountantEmployeeId(Long employeeId);

    List<CustomerGroup> findByGovEmployeeId(Long employeeId);
}
