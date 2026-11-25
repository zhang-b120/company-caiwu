package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaxLoginLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 税务登录记录Repository
 */
@Repository
public interface TaxLoginLogRepository extends JpaRepository<TaxLoginLog, Long> {
    
    /**
     * 根据账套ID查询登录记录
     */
    List<TaxLoginLog> findByAccountSetsIdOrderByLoginTimeDesc(Long accountSetsId);
    
    /**
     * 统计今日登录次数（按账套ID和手机号）
     */
    @Query("SELECT COUNT(l) FROM TaxLoginLog l WHERE l.accountSetsId = :accountSetsId " +
           "AND l.phoneNumber = :phoneNumber AND l.loginStatus = 'SUCCESS' " +
           "AND DATE(l.loginTime) = CURRENT_DATE")
    Long countTodayLoginByAccountSetsIdAndPhoneNumber(@Param("accountSetsId") Long accountSetsId, 
                                                       @Param("phoneNumber") String phoneNumber);
    
    /**
     * 统计今日登录次数（按账套ID）
     */
    @Query("SELECT COUNT(l) FROM TaxLoginLog l WHERE l.accountSetsId = :accountSetsId " +
           "AND l.loginStatus = 'SUCCESS' AND DATE(l.loginTime) = CURRENT_DATE")
    Long countTodayLoginByAccountSetsId(@Param("accountSetsId") Long accountSetsId);

    /**
     * 批量统计今日登录次数，一次查询返回所有账套的计数，避免 N+1
     */
    @Query("SELECT l.accountSetsId, COUNT(l) FROM TaxLoginLog l WHERE l.accountSetsId IN :accountSetsIds " +
           "AND l.loginStatus = 'SUCCESS' AND DATE(l.loginTime) = CURRENT_DATE GROUP BY l.accountSetsId")
    List<Object[]> countTodayLoginByAccountSetsIds(@Param("accountSetsIds") List<Long> accountSetsIds);
}
