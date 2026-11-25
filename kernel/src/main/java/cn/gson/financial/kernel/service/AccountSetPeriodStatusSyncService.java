package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.AccountSetsAccountingStatus;
import cn.gson.financial.kernel.model.entity.AccountSetsIndividualTaxStatus;
import cn.gson.financial.kernel.model.entity.AccountSetsInitialBalanceStatus;
import cn.gson.financial.kernel.model.entity.AccountSetsInvoiceStatus;
import cn.gson.financial.kernel.model.entity.AccountSetsTaxStatus;
import cn.gson.financial.kernel.model.entity.IndividualTax;
import cn.gson.financial.kernel.model.entity.TimedTaskInvoice;
import cn.gson.financial.kernel.model.entity.TimedTaskTax;
import cn.gson.financial.kernel.model.repo.AccountSetsAccountingStatusRepository;
import cn.gson.financial.kernel.model.repo.AccountSetsIndividualTaxStatusRepository;
import cn.gson.financial.kernel.model.repo.AccountSetsInitialBalanceStatusRepository;
import cn.gson.financial.kernel.model.repo.AccountSetsInvoiceStatusRepository;
import cn.gson.financial.kernel.model.repo.AccountSetsRepository;
import cn.gson.financial.kernel.model.repo.AccountSetsTaxStatusRepository;
import cn.gson.financial.kernel.model.repo.IndividualTaxRepository;
import cn.gson.financial.kernel.model.repo.TimedTaskInvoiceRepository;
import cn.gson.financial.kernel.model.repo.TimedTaskTaxRepository;
import cn.hutool.core.collection.CollUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 首页状态表同步：从任务表汇总状态，回填各状态主表。
 * 可定时或手动触发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountSetPeriodStatusSyncService {

    private final AccountSetsRepository accountSetsRepository;
    private final BalanceInitialService balanceInitialService;
    private final IndividualTaxRepository individualTaxRepository;
    private final TimedTaskInvoiceRepository timedTaskInvoiceRepository;
    private final TimedTaskTaxRepository timedTaskTaxRepository;
    private final AccountSetsInitialBalanceStatusRepository initialStatusRepository;
    private final AccountSetsAccountingStatusRepository accountingStatusRepository;
    private final AccountSetsInvoiceStatusRepository invoiceStatusRepository;
    private final AccountSetsTaxStatusRepository taxStatusRepository;
    private final AccountSetsIndividualTaxStatusRepository individualTaxStatusRepository;

    private static final int STATUS_NOT_STARTED = 0;
    private static final int STATUS_IN_PROGRESS = 1;
    private static final int STATUS_FAILED = 2;
    private static final int STATUS_SUCCESS = 3;

    /**
     * 任务表 result: 0 未执行 1 成功 2 失败；state: 0 未执行 1 执行完成。
     * 映射为：0 未执行 1 进行中 2 失败 3 成功
     */
    private static int taskResultToStatus(Integer state, Integer result) {
        if (state != null && state == 0) {
            return STATUS_IN_PROGRESS; // 进行中
        }
        if (result == null) {
            return STATUS_NOT_STARTED;
        }
        switch (result) {
            case 1:
                return STATUS_SUCCESS;
            case 2:
                return STATUS_FAILED;
            default:
                return STATUS_NOT_STARTED;
        }
    }

    /**
     * IndividualTax result: 0 未执行 1 失败 2 成功
     */
    private static int individualTaxResultToStatus(Integer result) {
        if (result == null) {
            return STATUS_NOT_STARTED;
        }
        switch (result) {
            case 2:
                return STATUS_SUCCESS;
            case 1:
                return STATUS_FAILED;
            default:
                return STATUS_NOT_STARTED;
        }
    }

    private static LocalDate toFirstDayOfMonth(Date date) {
        if (date == null) return null;
        LocalDate d = date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return d.withDayOfMonth(1);
    }

    private static String statusKey(Long asId, LocalDate p) {
        return asId + ":" + (p != null ? p.toString() : "");
    }

    @Transactional(rollbackFor = Exception.class)
    public void sync() {
        List<AccountSets> allAccountSets = accountSetsRepository.findAll();
        if (CollUtil.isEmpty(allAccountSets)) {
            return;
        }
        List<Long> accountSetsIds = allAccountSets.stream().map(AccountSets::getId).filter(Objects::nonNull).toList();
        Map<Long, Long> initialBalanceCountMap = balanceInitialService.countByAccountSetsIds(accountSetsIds);

        // 收集所有 (accountSetsId, period) 并构建状态聚合
        Map<String, StatusAgg> keyToStatus = new HashMap<>();

        // 1. 取票：timed_task_invoice 同组最大 id 的 result/state
        List<TimedTaskInvoice> invoiceList = timedTaskInvoiceRepository.findAll();
        Map<String, TimedTaskInvoice> invoiceMaxByGroup = invoiceList.stream()
                .filter(t -> t.getAccountSetsId() != null && t.getCheckDate() != null)
                .collect(Collectors.toMap(
                        t -> statusKey(t.getAccountSetsId(), toFirstDayOfMonth(t.getCheckDate())),
                        t -> t,
                        (a, b) -> a.getId() != null && b.getId() != null && a.getId() >= b.getId() ? a : b));
        for (Map.Entry<String, TimedTaskInvoice> e : invoiceMaxByGroup.entrySet()) {
            String k = e.getKey();
            TimedTaskInvoice t = e.getValue();
            keyToStatus.computeIfAbsent(k, x -> newAgg(t.getAccountSetsId(), toFirstDayOfMonth(t.getCheckDate())))
                    .setInvoiceStatus(taskResultToStatus(t.getState(), t.getResult()));
        }

        // 2. 报税：timed_task_tax 同组 (account_sets_id, check_date) 最大 id
        List<TimedTaskTax> taxList = timedTaskTaxRepository.findAll();
        Map<String, TimedTaskTax> taxMaxByGroup = taxList.stream()
                .filter(t -> t.getAccountSetsId() != null && t.getCheckDate() != null)
                .collect(Collectors.toMap(
                        t -> statusKey(t.getAccountSetsId(), t.getCheckDate()),
                        t -> t,
                        (a, b) -> a.getId() != null && b.getId() != null && a.getId() >= b.getId() ? a : b));
        for (Map.Entry<String, TimedTaskTax> e : taxMaxByGroup.entrySet()) {
            String k = e.getKey();
            TimedTaskTax t = e.getValue();
            keyToStatus.computeIfAbsent(k, x -> newAgg(t.getAccountSetsId(), t.getCheckDate()))
                    .setTaxStatus(taskResultToStatus(t.getState(), t.getResult()));
        }

        // 3. 个税：individual_tax 同组 (account_sets_id, period) 最大 id
        List<IndividualTax> individualTaxList = individualTaxRepository.findAll();
        Map<String, IndividualTax> itMaxByGroup = individualTaxList.stream()
                .filter(t -> t.getAccountSetsId() != null && t.getPeriod() != null)
                .collect(Collectors.toMap(
                        t -> statusKey(t.getAccountSetsId(), t.getPeriod().withDayOfMonth(1)),
                        t -> t,
                        (a, b) -> a.getId() != null && b.getId() != null && a.getId() >= b.getId() ? a : b));
        for (Map.Entry<String, IndividualTax> e : itMaxByGroup.entrySet()) {
            String k = e.getKey();
            IndividualTax t = e.getValue();
            LocalDate period = t.getPeriod() != null ? t.getPeriod().withDayOfMonth(1) : null;
            if (period == null) continue;
            keyToStatus.computeIfAbsent(k, x -> newAgg(t.getAccountSetsId(), period))
                    .setIndividualTaxStatus(individualTaxResultToStatus(t.getResult()));
        }

        // 4. 期初录入：每个账套启用账期一行，有 BalanceInitial 则 3 否则 0
        for (AccountSets as : allAccountSets) {
            if (as.getId() == null || as.getEnableDate() == null) continue;
            LocalDate enablePeriod = as.getEnableDate().withDayOfMonth(1);
            String k = statusKey(as.getId(), enablePeriod);
            StatusAgg row = keyToStatus.computeIfAbsent(k, x -> newAgg(as.getId(), enablePeriod));
            row.setInitialBalanceStatus(initialBalanceCountMap.getOrDefault(as.getId(), 0L) > 0 ? STATUS_SUCCESS : STATUS_NOT_STARTED);
        }

        // 5. 做账默认 0（未执行）
        for (StatusAgg row : keyToStatus.values()) {
            if (row.getAccountingStatus() == null) {
                row.setAccountingStatus(STATUS_NOT_STARTED);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        // 同步到新状态主表
        for (StatusAgg row : keyToStatus.values()) {
            Long asId = row.getAccountSetsId();
            LocalDate period = row.getPeriod();
            if (asId == null || period == null) {
                continue;
            }

            // 做账
            Integer accounting = row.getAccountingStatus();
            upsertAccountingStatus(asId, period, accounting, now);

            // 取票
            Integer invoice = row.getInvoiceStatus();
            upsertInvoiceStatus(asId, period, invoice, now);

            // 报税
            Integer tax = row.getTaxStatus();
            upsertTaxStatus(asId, period, tax, now);

            // 个税
            Integer individual = row.getIndividualTaxStatus();
            upsertIndividualTaxStatus(asId, period, individual, now);
        }

        // 期初（按账套维度）
        for (AccountSets as : allAccountSets) {
            if (as.getId() == null) {
                continue;
            }
            Long asId = as.getId();
            Integer status = STATUS_NOT_STARTED;
            if (initialBalanceCountMap.getOrDefault(asId, 0L) > 0) {
                status = STATUS_SUCCESS;
            }
            upsertInitialStatus(asId, status, now);
        }

        log.info("account_set_period_status sync done, rows: {}", keyToStatus.size());
    }

    private StatusAgg newAgg(Long accountSetsId, LocalDate period) {
        StatusAgg r = new StatusAgg();
        r.setAccountSetsId(accountSetsId);
        r.setPeriod(period);
        return r;
    }

    private int statusToResult(Integer status) {
        if (status == null || status <= 0) {
            return 0;
        }
        if (status == STATUS_SUCCESS) {
            return 1;
        }
        if (status == STATUS_FAILED) {
            return 2;
        }
        // 进行中默认视为未执行完成
        return 0;
    }

    private void upsertInitialStatus(Long accountSetsId, Integer status, LocalDateTime now) {
        int s = status != null ? status : STATUS_NOT_STARTED;
        int r = statusToResult(status);
        AccountSetsInitialBalanceStatus entity = initialStatusRepository.findByAccountSetsId(accountSetsId)
                .orElseGet(() -> {
                    AccountSetsInitialBalanceStatus e = new AccountSetsInitialBalanceStatus();
                    e.setAccountSetsId(accountSetsId);
                    e.setCreateTime(now);
                    return e;
                });
        entity.setStatus(s);
        entity.setResult(r);
        entity.setUpdateTime(now);
        initialStatusRepository.save(entity);
    }

    private void upsertAccountingStatus(Long accountSetsId, LocalDate period, Integer status, LocalDateTime now) {
        int s = status != null ? status : STATUS_NOT_STARTED;
        int r = statusToResult(status);
        AccountSetsAccountingStatus entity = accountingStatusRepository.findByAccountSetsIdAndPeriod(accountSetsId, period)
                .orElseGet(() -> {
                    AccountSetsAccountingStatus e = new AccountSetsAccountingStatus();
                    e.setAccountSetsId(accountSetsId);
                    e.setPeriod(period);
                    e.setCreateTime(now);
                    return e;
                });
        entity.setStatus(s);
        entity.setResult(r);
        entity.setUpdateTime(now);
        accountingStatusRepository.save(entity);
    }

    private void upsertInvoiceStatus(Long accountSetsId, LocalDate period, Integer status, LocalDateTime now) {
        int s = status != null ? status : STATUS_NOT_STARTED;
        int r = statusToResult(status);
        AccountSetsInvoiceStatus entity = invoiceStatusRepository.findByAccountSetsIdAndPeriod(accountSetsId, period)
                .orElseGet(() -> {
                    AccountSetsInvoiceStatus e = new AccountSetsInvoiceStatus();
                    e.setAccountSetsId(accountSetsId);
                    e.setPeriod(period);
                    e.setCreateTime(now);
                    return e;
                });
        entity.setStatus(s);
        entity.setResult(r);
        entity.setUpdateTime(now);
        invoiceStatusRepository.save(entity);
    }

    private void upsertTaxStatus(Long accountSetsId, LocalDate period, Integer status, LocalDateTime now) {
        int s = status != null ? status : STATUS_NOT_STARTED;
        int r = statusToResult(status);
        AccountSetsTaxStatus entity = taxStatusRepository.findByAccountSetsIdAndPeriod(accountSetsId, period)
                .orElseGet(() -> {
                    AccountSetsTaxStatus e = new AccountSetsTaxStatus();
                    e.setAccountSetsId(accountSetsId);
                    e.setPeriod(period);
                    e.setCreateTime(now);
                    return e;
                });
        entity.setStatus(s);
        entity.setResult(r);
        entity.setUpdateTime(now);
        taxStatusRepository.save(entity);
    }

    private void upsertIndividualTaxStatus(Long accountSetsId, LocalDate period, Integer status, LocalDateTime now) {
        int s = status != null ? status : STATUS_NOT_STARTED;
        int r = statusToResult(status);
        AccountSetsIndividualTaxStatus entity = individualTaxStatusRepository.findByAccountSetsIdAndPeriod(accountSetsId, period)
                .orElseGet(() -> {
                    AccountSetsIndividualTaxStatus e = new AccountSetsIndividualTaxStatus();
                    e.setAccountSetsId(accountSetsId);
                    e.setPeriod(period);
                    e.setCreateTime(now);
                    return e;
                });
        entity.setStatus(s);
        entity.setResult(r);
        entity.setUpdateTime(now);
        individualTaxStatusRepository.save(entity);
    }

    /**
     * 内部使用的状态聚合对象，替代旧的 AccountSetPeriodStatus 实体。
     */
    private static class StatusAgg {
        private Long accountSetsId;
        private LocalDate period;
        private Integer initialBalanceStatus;
        private Integer accountingStatus;
        private Integer invoiceStatus;
        private Integer taxStatus;
        private Integer individualTaxStatus;

        public Long getAccountSetsId() {
            return accountSetsId;
        }

        public void setAccountSetsId(Long accountSetsId) {
            this.accountSetsId = accountSetsId;
        }

        public LocalDate getPeriod() {
            return period;
        }

        public void setPeriod(LocalDate period) {
            this.period = period;
        }

        public Integer getInitialBalanceStatus() {
            return initialBalanceStatus;
        }

        public void setInitialBalanceStatus(Integer initialBalanceStatus) {
            this.initialBalanceStatus = initialBalanceStatus;
        }

        public Integer getAccountingStatus() {
            return accountingStatus;
        }

        public void setAccountingStatus(Integer accountingStatus) {
            this.accountingStatus = accountingStatus;
        }

        public Integer getInvoiceStatus() {
            return invoiceStatus;
        }

        public void setInvoiceStatus(Integer invoiceStatus) {
            this.invoiceStatus = invoiceStatus;
        }

        public Integer getTaxStatus() {
            return taxStatus;
        }

        public void setTaxStatus(Integer taxStatus) {
            this.taxStatus = taxStatus;
        }

        public Integer getIndividualTaxStatus() {
            return individualTaxStatus;
        }

        public void setIndividualTaxStatus(Integer individualTaxStatus) {
            this.individualTaxStatus = individualTaxStatus;
        }
    }
}
