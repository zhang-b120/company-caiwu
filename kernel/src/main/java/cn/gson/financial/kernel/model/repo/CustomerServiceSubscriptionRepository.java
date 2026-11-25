package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.CustomerServiceSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * FN-NEW-03 客户服务订阅 Repository
 */
public interface CustomerServiceSubscriptionRepository
        extends JpaRepository<CustomerServiceSubscription, Long> {

    List<CustomerServiceSubscription> findByCustomerIdOrderByCreatedAtAsc(Long customerId);

    Optional<CustomerServiceSubscription> findByCustomerIdAndServiceType(Long customerId, String serviceType);
}
