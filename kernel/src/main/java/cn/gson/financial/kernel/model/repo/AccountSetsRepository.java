package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.enums.VatType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountSetsRepository extends QuerydslPredicateExecutor<AccountSets>, JpaRepository<AccountSets, Long> {

    Optional<AccountSets> findByCreditCode(String creditCode);

    /**
     * 多用户场景：按用户 ID 列表查未停用账套，同时下推过滤条件。
     */
    @Query("""
        SELECT a FROM AccountSets a
        WHERE a.id IN (SELECT ua.accountSetsId FROM UserAccountSets ua WHERE ua.userId IN :userIds)
          AND (a.deactivated IS NULL OR a.deactivated = false)
          AND (:keyword IS NULL OR a.companyName LIKE CONCAT('%', :keyword, '%')
                                OR a.creditCode  LIKE CONCAT('%', :keyword, '%'))
          AND (:vatType IS NULL OR a.vatType = :vatType)
          AND (:province IS NULL OR a.province = :province)
        ORDER BY COALESCE(a.starRating, 0) DESC, a.companyName ASC
        """)
    List<AccountSets> findActiveByUserIdsAndFilters(
            @Param("userIds") List<Long> userIds,
            @Param("keyword") String keyword,
            @Param("vatType") VatType vatType,
            @Param("province") String province);

    /**
     * 单条 SQL 替代原来的「查用户账套ID → IN 查账套详情 → Java 内存过滤」三步操作。
     * 通过子查询关联 user_account_sets，同时将 keyword / vatType / province 过滤下推到 SQL。
     * 所有参数传 null 即跳过该条件。
     */
    @Query("""
        SELECT a FROM AccountSets a
        WHERE a.id IN (SELECT ua.accountSetsId FROM UserAccountSets ua WHERE ua.userId = :userId)
          AND (a.deactivated IS NULL OR a.deactivated = false)
          AND (:keyword IS NULL OR a.companyName LIKE CONCAT('%', :keyword, '%')
                                OR a.creditCode  LIKE CONCAT('%', :keyword, '%'))
          AND (:vatType IS NULL OR a.vatType = :vatType)
          AND (:province IS NULL OR a.province = :province)
        ORDER BY COALESCE(a.starRating, 0) DESC, a.companyName ASC
        """)
    List<AccountSets> findActiveByUserAndFilters(
            @Param("userId") Long userId,
            @Param("keyword") String keyword,
            @Param("vatType") VatType vatType,
            @Param("province") String province);

    /**
     * userId 为 null 时查全部未停用账套（管理员场景），同样下推过滤。
     */
    @Query("""
        SELECT a FROM AccountSets a
        WHERE (a.deactivated IS NULL OR a.deactivated = false)
          AND (:keyword IS NULL OR a.companyName LIKE CONCAT('%', :keyword, '%')
                                OR a.creditCode  LIKE CONCAT('%', :keyword, '%'))
          AND (:vatType IS NULL OR a.vatType = :vatType)
          AND (:province IS NULL OR a.province = :province)
        ORDER BY COALESCE(a.starRating, 0) DESC, a.companyName ASC
        """)
    List<AccountSets> findActiveByFilters(
            @Param("keyword") String keyword,
            @Param("vatType") VatType vatType,
            @Param("province") String province);

    /**
     * 根据税号列表批量匹配未停用的账套
     */
    @Query("""
        SELECT a FROM AccountSets a
        WHERE (a.deactivated IS NULL OR a.deactivated = false)
          AND a.creditCode IN :creditCodes
        """)
    List<AccountSets> findActiveByCreditCodes(@Param("creditCodes") List<String> creditCodes);

    /**
     * 根据税号列表 + 用户ID列表批量匹配未停用的账套
     */
    @Query("""
        SELECT a FROM AccountSets a
        WHERE a.id IN (SELECT ua.accountSetsId FROM UserAccountSets ua WHERE ua.userId IN :userIds)
          AND (a.deactivated IS NULL OR a.deactivated = false)
          AND a.creditCode IN :creditCodes
        """)
    List<AccountSets> findActiveByCreditCodesAndUserIds(
            @Param("creditCodes") List<String> creditCodes,
            @Param("userIds") List<Long> userIds);
}
