package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaxLoginAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 税务登录账号Repository
 */
@Repository
public interface TaxLoginAccountRepository extends JpaRepository<TaxLoginAccount, Long> {
    
    /**
     * 根据账套ID和登录类型查询
     */
    List<TaxLoginAccount> findByAccountSetsIdAndLoginType(Long accountSetsId, String loginType);
    
    /**
     * 根据账套ID查询所有登录账号
     */
    List<TaxLoginAccount> findByAccountSetsId(Long accountSetsId);
    
    /**
     * 根据账套ID和状态查询
     */
    List<TaxLoginAccount> findByAccountSetsIdAndStatus(Long accountSetsId, String status);
}
