package cn.gson.financial.account.repository;

import cn.gson.financial.account.entity.InitialBalanceEntry;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 期初余额数据访问层接口
 * 负责期初余额数据的增删改查操作
 */
@Repository
public interface InitialBalanceRepository {

    /**
     * 根据科目编码查询期初余额
     *
     * @param accountCode 科目编码
     * @return 期初余额记录
     */
    Optional<InitialBalanceEntry> findByAccountCode(String accountCode);

    /**
     * 根据科目名称查询期初余额
     *
     * @param accountName 科目名称
     * @return 期初余额记录列表
     */
    List<InitialBalanceEntry> findByAccountName(String accountName);

    /**
     * 查询所有期初余额记录
     *
     * @return 期初余额记录列表
     */
    List<InitialBalanceEntry> findAll();

    /**
     * 保存期初余额记录
     *
     * @param entry 期初余额记录
     * @return 保存后的期初余额记录
     */
    InitialBalanceEntry save(InitialBalanceEntry entry);

    /**
     * 批量保存期初余额记录
     *
     * @param entries 期初余额记录列表
     * @return 保存后的期初余额记录列表
     */
    List<InitialBalanceEntry> saveAll(List<InitialBalanceEntry> entries);

    /**
     * 根据科目编码删除期初余额记录
     *
     * @param accountCode 科目编码
     */
    void deleteByAccountCode(String accountCode);

    /**
     * 清空所有期初余额记录
     */
    void deleteAll();

    /**
     * 根据科目编码查询期初余额是否存在
     *
     * @param accountCode 科目编码
     * @return 存在返回true，不存在返回false
     */
    boolean existsByAccountCode(String accountCode);
} 