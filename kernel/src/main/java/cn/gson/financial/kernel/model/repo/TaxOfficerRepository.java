package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaxOfficer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TaxOfficerRepository extends JpaRepository<TaxOfficer, Long> {

    /** 按账套查询所有办税员，默认的排在最前 */
    List<TaxOfficer> findByAccountSetIdOrderByDefaultOfficerDescCreatedAtAsc(Long accountSetId);

    /** 查找默认办税员 */
    Optional<TaxOfficer> findByAccountSetIdAndDefaultOfficerTrue(Long accountSetId);

    /** 按账套+手机号查找（用于去重） */
    Optional<TaxOfficer> findByAccountSetIdAndPhone(Long accountSetId, String phone);

    /** 判断账套是否已配置办税员 */
    boolean existsByAccountSetId(Long accountSetId);

    /** 清除该账套所有默认标记（设置新默认前调用） */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE TaxOfficer t SET t.defaultOfficer = FALSE WHERE t.accountSetId = :accountSetId")
    void clearDefaultByAccountSetId(@Param("accountSetId") Long accountSetId);

    /** 按登录渠道查询所有账套的办税员（全量，用于批量下发凭证列表） */
    List<TaxOfficer> findByLoginType(String loginType);

    /** 按账套列表和登录渠道查询办税员（用于统计已选账套匹配数） */
    List<TaxOfficer> findByAccountSetIdInAndLoginType(List<Long> accountSetIds, String loginType);

    /** 按账套列表查询所有默认办税员 */
    List<TaxOfficer> findByAccountSetIdInAndDefaultOfficerTrue(List<Long> accountSetIds);
}
