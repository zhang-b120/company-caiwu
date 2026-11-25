package cn.gson.financial.account.repository.impl;

import cn.gson.financial.account.entity.InitialBalanceEntry;
import cn.gson.financial.account.repository.InitialBalanceRepository;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class InitialBalanceRepositoryImpl implements InitialBalanceRepository {

    private final ConcurrentHashMap<String, InitialBalanceEntry> balanceMap = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    @Override
    public Optional<InitialBalanceEntry> findByAccountCode(String accountCode) {
        return Optional.ofNullable(balanceMap.get(accountCode));
    }

    @Override
    public List<InitialBalanceEntry> findByAccountName(String accountName) {
        return balanceMap.values().stream()
                .filter(entry -> accountName.equals(entry.getAccountName()))
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    public List<InitialBalanceEntry> findAll() {
        return new ArrayList<>(balanceMap.values());
    }

    @Override
    public InitialBalanceEntry save(InitialBalanceEntry entry) {
        if (entry.getAccountNumber() == null) {
            entry.setAccountNumber("TEMP_" + idGenerator.getAndIncrement());
        }
        balanceMap.put(entry.getAccountNumber(), entry);
        return entry;
    }

    @Override
    public List<InitialBalanceEntry> saveAll(List<InitialBalanceEntry> entries) {
        List<InitialBalanceEntry> saved = new ArrayList<>();
        for (InitialBalanceEntry entry : entries) {
            saved.add(save(entry));
        }
        return saved;
    }

    @Override
    public void deleteByAccountCode(String accountCode) {
        balanceMap.remove(accountCode);
    }

    @Override
    public void deleteAll() {
        balanceMap.clear();
    }

    @Override
    public boolean existsByAccountCode(String accountCode) {
        return balanceMap.containsKey(accountCode);
    }
}