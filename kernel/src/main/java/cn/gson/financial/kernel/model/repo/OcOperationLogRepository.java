package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.OcOperationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OcOperationLogRepository extends JpaRepository<OcOperationLog, Long> {

    /**
     * 查询需要排除的省份（指定登录凭证）
     */
    @Query(value = """
            SELECT province
            FROM fxy_financial_oc_operation_log
            WHERE login_credential = :loginCredential
              AND operation_time >= NOW() - INTERVAL :timeWindow MINUTE
            GROUP BY province
            HAVING COUNT(*) >= :maxCount
            """, nativeQuery = true)
    List<String> findExcludedProvinces(
            @Param("loginCredential") String loginCredential,
            @Param("maxCount") int maxCount,
            @Param("timeWindow") int timeWindow
    );

    /**
     * 查询所有超限的 (login_credential, province) 组合，用于 next-account 内部过滤。
     * 返回每行格式为 "login_credential|province"
     */
    @Query(value = """
            SELECT CONCAT(login_credential, '|', province)
            FROM fxy_financial_oc_operation_log
            WHERE operation_time >= NOW() - INTERVAL :timeWindow MINUTE
            GROUP BY login_credential, province
            HAVING COUNT(*) >= :maxCount
            """, nativeQuery = true)
    List<String> findExcludedCredentialProvinces(
            @Param("maxCount") int maxCount,
            @Param("timeWindow") int timeWindow
    );
}
