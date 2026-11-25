package cn.gson.financial.account.repository;

import cn.gson.financial.account.entity.Account;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 科目数据访问层接口
 * 负责科目数据的增删改查操作
 */
@Repository
public interface AccountRepository {

    /**
     * 根据科目编码查询科目信息
     *
     * @param code 科目编码
     * @return 科目信息
     */
    Optional<Account> findByCode(String code);

    /**
     * 根据科目名称查询科目信息
     *
     * @param name 科目名称
     * @return 科目信息列表
     */
    List<Account> findByName(String name);

    /**
     * 根据父科目编码查询子科目列表
     *
     * @param parentCode 父科目编码
     * @return 子科目列表
     */
    List<Account> findByParentCode(String parentCode);

    /**
     * 查询所有科目信息
     *
     * @return 科目列表
     */
    List<Account> findAll();

    /**
     * 保存科目信息
     *
     * @param account 科目信息
     * @return 保存后的科目信息
     */
    Account save(Account account);

    /**
     * 批量保存科目信息
     *
     * @param accounts 科目列表
     * @return 保存后的科目列表
     */
    List<Account> saveAll(List<Account> accounts);

    /**
     * 根据科目编码删除科目
     *
     * @param code 科目编码
     */
    void deleteByCode(String code);

    /**
     * 根据科目层级查询科目列表
     *
     * @param level 科目层级 (1-4)
     * @return 科目列表
     */
    List<Account> findByLevel(int level);

    /**
     * 查询科目编码是否存在
     *
     * @param code 科目编码
     * @return 存在返回true，不存在返回false
     */
    boolean existsByCode(String code);
} 