package cn.gson.financial.account.repository.impl;

import cn.gson.financial.account.entity.Account;
import cn.gson.financial.account.repository.AccountRepository;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 科目Repository的简单内存实现
 * 注意：这是一个演示实现，实际项目中应该使用真实的数据库
 */
@Repository
public class AccountRepositoryImpl implements AccountRepository {

    // 使用内存存储作为演示，实际项目中应该使用数据库
    private final ConcurrentHashMap<String, Account> accountMap = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    @Override
    public Optional<Account> findByCode(String code) {
        return Optional.ofNullable(accountMap.get(code));
    }

    @Override
    public List<Account> findByName(String name) {
        return accountMap.values().stream()
                .filter(account -> name.equals(account.getName()))
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    public List<Account> findByParentCode(String parentCode) {
        return accountMap.values().stream()
                .filter(account -> parentCode.equals(account.getParentCode()))
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    public List<Account> findAll() {
        return new ArrayList<>(accountMap.values());
    }

    @Override
    public Account save(Account account) {
        if (account.getCode() == null) {
            // 如果没有编码，生成一个临时编码
            account.setCode("TEMP_" + idGenerator.getAndIncrement());
        }
        accountMap.put(account.getCode(), account);
        return account;
    }

    @Override
    public List<Account> saveAll(List<Account> accounts) {
        List<Account> savedAccounts = new ArrayList<>();
        for (Account account : accounts) {
            savedAccounts.add(save(account));
        }
        return savedAccounts;
    }

    @Override
    public void deleteByCode(String code) {
        accountMap.remove(code);
    }

    @Override
    public List<Account> findByLevel(int level) {
        return accountMap.values().stream()
                .filter(account -> getCodeLevel(account.getCode()) == level)
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    public boolean existsByCode(String code) {
        return accountMap.containsKey(code);
    }

    /**
     * 辅助方法：根据编码长度判断科目层级
     */
    private int getCodeLevel(String code) {
        if (code == null) return 0;
        int length = code.length();
        if (length == 4) return 1;
        if (length == 7) return 2;
        if (length == 10) return 3;
        if (length == 12) return 4;
        return 0;
    }

    /**
     * 初始化一些示例数据
     */
    public void initializeSampleData() {
        // 一级科目
        Account cash = new Account("1001", "库存现金", "借", null, null);
        Account bank = new Account("1002", "银行存款", "借", null, null);
        Account receivables = new Account("1122", "应收账款", "借", null, null);
        
        // 二级科目
        Account cashSpare = new Account("1001001", "库存现金-备用金", "借", "1001", "库存现金");
        Account bankMain = new Account("1002001", "银行存款-基本户", "借", "1002", "银行存款");
        Account receivablesCustomer = new Account("1122001", "应收账款-客户", "借", "1122", "应收账款");
        
        save(cash);
        save(bank);
        save(receivables);
        save(cashSpare);
        save(bankMain);
        save(receivablesCustomer);
    }
} 