package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.Customer;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, PrimaryKey> {

    /**
     * FN2-T2（135）：扫码 bridge 按购方税号 match 已有客户。
     * Customer 复合主键 (id + accountSetsId)，限定账套内查；多账套场景同税号可能存多条。
     */
    @Query("SELECT c FROM Customer c WHERE c.accountSetsId = :accountSetsId AND c.creditCode = :creditCode")
    Optional<Customer> findByAccountSetsIdAndCreditCode(@Param("accountSetsId") Long accountSetsId,
                                                       @Param("creditCode") String creditCode);
}
