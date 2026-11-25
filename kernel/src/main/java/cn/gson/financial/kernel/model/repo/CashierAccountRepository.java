package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.CashierAccount;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashierAccountRepository extends JpaRepository<CashierAccount, PrimaryKey> {

    java.util.Optional<CashierAccount> findFirstByAccountSetsIdAndName(Long accountSetsId, String name);

    java.util.Optional<CashierAccount> findFirstByAccountSetsIdAndTypeOrderByCodeDesc(
            Long accountSetsId, CashierAccount.CashierAccountType type);

    java.util.List<CashierAccount> findByAccountSetsIdAndType(
            Long accountSetsId, CashierAccount.CashierAccountType type);

    java.util.Optional<CashierAccount> findFirstByAccountSetsIdAndCardNumber(
            Long accountSetsId, String cardNumber);
}
