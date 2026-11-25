package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.model.dto.BalanceDto;
import cn.gson.financial.kernel.model.dto.CurrencyDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccessRules;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import cn.gson.financial.kernel.model.mapper.BalanceMapper;
import cn.gson.financial.kernel.model.vo.SubjectVo;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.tool.RedisLock;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.TimeInterval;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.ReflectUtil;
import com.alibaba.fastjson.JSON;
import io.sentry.Sentry;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 余额表计算</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年06月05日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceCalcService extends AbsService {
    public static final String FXY_FINANCIAL_BALANCE = "fxy_financial_balance";

    public static final String FXY_FINANCIAL_BALANCE_CASH_FLOW = "fxy_financial_balance_cash_flow";

    @Resource
    private JpaTransactionManager transactionManager;

    private final BalanceMapper balanceMapper;

    private final SubjectService subjectService;

    private final QBalance qBalance = QBalance.balance;

    private final QBalanceCashFlow qBalanceCashFlow = QBalanceCashFlow.balanceCashFlow;

    private final QBalanceInitial qBalanceInitial = QBalanceInitial.balanceInitial;

    private final QAccountSets qAccountSets = QAccountSets.accountSets;

    private final QVoucher qVoucher = QVoucher.voucher;

    private final QReportCashFlowDraft qReportCashFlowDraft = QReportCashFlowDraft.reportCashFlowDraft;

    private final CurrencyService currencyService;

    private final StringRedisTemplate redisTemplate;

    /**
     * 余额重算分布式锁参数（FN-FIX-BALANCE-CALC-CONCURRENCY）。
     * <p>TTL 必须远大于单账套最坏重算耗时：否则长重算进行中锁会自动过期，第二个重算趁虚
     * 抢到锁，与第一个的 delete+insert 交叉提交，写出重复 / 半成品余额行（本工单根因之一）。
     * 真账套整任务曾耗时 ~405s，故 TTL 取 15 分钟留足余量；崩溃未释放最坏 15 分钟后自动恢复。
     */
    private static final int CALC_LOCK_EXPIRE_MS = 15 * 60 * 1000;
    /**
     * 抢锁等待时间：同账套并发重算时排队等待而非立即跳过；超时则抛可观测异常，绝不静默读半成品。
     */
    private static final int CALC_LOCK_ACQUIRE_MS = 30 * 1000;

    /**
     * 同步重算余额。口径与 {@link #calc} 完全一致，仅线程模型不同（本方法在调用者线程内同步完成）。
     * <p>verify / 读后即用等「必须重算落定后再读」的入口走本方法，保证读到的是完整重算结果。
     *
     * @param accountSetsId 账套ID
     * @param period        期间
     */
    public void calcSynchronization(Long accountSetsId, YearMonth period) {
        this.doCalc(accountSetsId, period);
    }

    /**
     * 计算余额（异步）。触发式刷新走本方法（{@code @Async} 后台线程）。
     * <p>与 {@link #calcSynchronization} 共用同一份 {@link #doCalc} 实现，async/sync 语义显式区分，
     * 不再依赖「自调用绕过 Spring 代理」这种隐式同步技巧。
     *
     * @param accountSetsId 账套ID
     * @param period        期间
     */
    @Async
    public void calc(Long accountSetsId, YearMonth period) {
        this.doCalc(accountSetsId, period);
    }

    /**
     * 余额重算核心实现（同步）。
     * <p>分布式锁串行化同账套重算；delete + generateBalance + commit 在同一事务内原子完成，
     * 隔离级别 READ_COMMITTED 保证其他读者在提交前读不到中间态。
     * <p>FN-FIX-BALANCE-CALC-CONCURRENCY：抢锁失败 / 事务失败均不再静默 return，
     * 而是抛出 {@link ServiceException}（日志 + Sentry 可观测），防止调用方读到未重算 / 半成品余额。
     *
     * @param accountSetsId 账套ID
     * @param period        期间
     */
    private void doCalc(Long accountSetsId, YearMonth period) {
        RedisLock redisLock = new RedisLock(redisTemplate,
                this.getClass().getName() + ".lock" + accountSetsId,
                CALC_LOCK_ACQUIRE_MS, CALC_LOCK_EXPIRE_MS);
        boolean locked = false;
        try {
            try {
                locked = redisLock.lock();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ServiceException(ie, "余额重算抢锁被中断：accountSetsId=%s,period=%s", accountSetsId, period);
            }
            if (!locked) {
                String msg = "余额重算抢锁超时(" + CALC_LOCK_ACQUIRE_MS + "ms)：accountSetsId=" + accountSetsId + ",period=" + period;
                log.warn(msg);
                Sentry.captureMessage(msg);
                throw new ServiceException(msg);
            }
            TransactionStatus transaction = this.beginTransaction();
            try {
                LocalDate enableDate = bqf.selectFrom(qAccountSets).select(qAccountSets.enableDate).where(qAccountSets.id.eq(accountSetsId)).fetchFirst();
                //账套启用期间
                YearMonth enableMonth = YearMonth.from(enableDate);
                
                // 获取当前会计期间，确保计算范围包含当前会计期间
                LocalDate currentAccountDate = bqf.selectFrom(qAccountSets).select(qAccountSets.currentAccountDate).where(qAccountSets.id.eq(accountSetsId)).fetchFirst();
                
                LocalDate maxVoucherDate = bqf.selectFrom(qVoucher).select(qVoucher.voucherDate.max()).where(qVoucher.accountSetsId.eq(accountSetsId)).fetchFirst();
                if (maxVoucherDate != null) {
                    maxVoucherDate = maxVoucherDate.plusMonths(1);
                } else {
                    maxVoucherDate = enableDate;
                }
                
                //账套最后期间：取最大凭证日期和当前会计期间中的较大者，确保包含当前会计期间
                if (currentAccountDate != null && currentAccountDate.isAfter(maxVoucherDate)) {
                    maxVoucherDate = currentAccountDate;
                }
                YearMonth endCheckMonth = YearMonth.from(maxVoucherDate);

                log.info("\n\ncalc：accountSetsId：{}，enableMonth：{}，endCheckMonth：{}，period：{}\n", accountSetsId, enableMonth, endCheckMonth, period);

                //本币
                CurrencyDto currencyLocal = currencyService.fetchLocal(accountSetsId);

                Map<Long, SubjectVo> localSubjectMap = subjectService.list(accountSetsId).stream().collect(Collectors.toMap(AbsEntity::getId, s -> s));
                localSubjectMap.forEach((k, v) -> {
                    if (v.getParentId() != null && v.getParentId() != 0L) {
                        localSubjectMap.get(v.getParentId()).getChildren().add(v);
                    }
                });

                TimeInterval timer = DateUtil.timer();
                timer.start("generateBalance");

                YearMonth startMonth = period;
                YearMonth endMonth = endCheckMonth;
                //变更期间大于最后的期间
                if (period.isAfter(endCheckMonth)) {
                    startMonth = endCheckMonth;
                    endMonth = period;
                }

                jqf.delete(qBalance)
                        .where(qBalance.accountSetsId.eq(accountSetsId)
                                .and(qBalance.date.goe(startMonth.atDay(1))))
                        .execute();

                jqf.delete(qBalanceCashFlow)
                        .where(qBalanceCashFlow.accountSetsId.eq(accountSetsId)
                                .and(qBalanceCashFlow.date.goe(startMonth.atDay(1))))
                        .execute();
                do {
                    //递归计算后面月份的余额
                    timer.start(startMonth.toString());
                    generateBalance(accountSetsId, startMonth, enableMonth, currencyLocal, localSubjectMap, FXY_FINANCIAL_BALANCE);
                    generateBalance(accountSetsId, startMonth, enableMonth, currencyLocal, localSubjectMap, FXY_FINANCIAL_BALANCE_CASH_FLOW);
                    log.info("\n\nasId:{}，\n计算期间：{}，余额计算耗时：{}ms\n", accountSetsId, startMonth, timer.intervalMs(startMonth.toString()));
                    startMonth = startMonth.plusMonths(1);
                } while (startMonth.isBefore(endMonth) || startMonth.equals(endMonth));
                log.info("\n\nasId:{}，\n计算开始期间：{}，\n计算结束期间：{}，\n启用期间：{}，\ngenerateBalance余额计算耗时：{}ms\n", accountSetsId, period, endMonth, enableMonth, timer.intervalMs("generateBalance"));

                transactionManager.commit(transaction);
            } catch (Exception e) {
                log.error("accountSetsId：{},period：{},余额表计算失败~", accountSetsId, period, e);
                Sentry.captureException(e);
                transactionManager.rollback(transaction);
                // 不静默吞掉：事务失败必须可观测，避免调用方误以为重算成功而读到旧/半成品余额
                throw new ServiceException(e, "余额表计算失败：accountSetsId=%s,period=%s", accountSetsId, period);
            }
        } finally {
            if (locked) {
                redisLock.unlock();
            }
        }
    }

    public void calcCashFlow(Long accountSetsId, YearMonth period) {
        RedisLock redisLock = new RedisLock(redisTemplate,
                this.getClass().getName() + ".calcCashFlow.lock" + accountSetsId,
                CALC_LOCK_ACQUIRE_MS, CALC_LOCK_EXPIRE_MS);
        boolean locked = false;
        try {
            try {
                locked = redisLock.lock();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ServiceException(ie, "现金流余额重算抢锁被中断：accountSetsId=%s,period=%s", accountSetsId, period);
            }
            if (!locked) {
                String msg = "现金流余额重算抢锁超时(" + CALC_LOCK_ACQUIRE_MS + "ms)：accountSetsId=" + accountSetsId + ",period=" + period;
                log.warn(msg);
                Sentry.captureMessage(msg);
                throw new ServiceException(msg);
            }
            TransactionStatus transaction = this.beginTransaction();
            try {
                LocalDate enableDate = bqf.selectFrom(qAccountSets).select(qAccountSets.enableDate).where(qAccountSets.id.eq(accountSetsId)).fetchFirst();
                //账套启用期间
                YearMonth enableMonth = YearMonth.from(enableDate);
                LocalDate maxVoucherDate = bqf.selectFrom(qVoucher).select(qVoucher.voucherDate.max()).where(qVoucher.accountSetsId.eq(accountSetsId)).fetchFirst();
                if (maxVoucherDate != null) {
                    maxVoucherDate = maxVoucherDate.plusMonths(1);
                } else {
                    maxVoucherDate = enableDate;
                }
                //账套最后期间
                YearMonth endCheckMonth = YearMonth.from(maxVoucherDate);

                //本币
                CurrencyDto currencyLocal = currencyService.fetchLocal(accountSetsId);

                Map<Long, SubjectVo> localSubjectMap = subjectService.list(accountSetsId).stream().collect(Collectors.toMap(AbsEntity::getId, s -> s));
                localSubjectMap.forEach((k, v) -> {
                    if (v.getParentId() != null && v.getParentId() != 0L) {
                        localSubjectMap.get(v.getParentId()).getChildren().add(v);
                    }
                });

                TimeInterval timer = DateUtil.timer();
                timer.start("generateCalcCashFlowBalance");

                YearMonth startMonth = period;
                YearMonth endMonth = endCheckMonth;
                //变更期间大于最后的期间
                if (period.isAfter(endCheckMonth)) {
                    startMonth = endCheckMonth;
                    endMonth = period;
                }

                jqf.delete(qBalanceCashFlow)
                        .where(qBalanceCashFlow.accountSetsId.eq(accountSetsId)
                                .and(qBalanceCashFlow.date.goe(startMonth.atDay(1))))
                        .execute();
                do {
                    //递归计算后面月份的余额
                    timer.start(startMonth.toString());
                    generateBalance(accountSetsId, startMonth, enableMonth, currencyLocal, localSubjectMap, FXY_FINANCIAL_BALANCE_CASH_FLOW);
                    startMonth = startMonth.plusMonths(1);
                } while (startMonth.isBefore(endMonth) || startMonth.equals(endMonth));

                transactionManager.commit(transaction);
            } catch (Exception e) {
                log.error("calcCashFlow accountSetsId：{},period：{},余额表计算失败~", accountSetsId, period, e);
                Sentry.captureException(e);
                transactionManager.rollback(transaction);
                throw new ServiceException(e, "现金流余额表计算失败：accountSetsId=%s,period=%s", accountSetsId, period);
            }
        } finally {
            if (locked) {
                redisLock.unlock();
            }
        }
    }

    /**
     * 生成余额表
     *
     * @param accountSetsId    账套ID
     * @param period           期间
     * @param enableMonth      启用期间
     * @param localCurrency    本币
     * @param localSubjectMap  科目
     * @param balanceTableName 余额表名称
     */
    private void generateBalance(Long accountSetsId, YearMonth period, YearMonth enableMonth, CurrencyDto localCurrency, Map<Long, SubjectVo> localSubjectMap, String balanceTableName) {
        LocalDate startDate = period.minusMonths(1).atDay(1);

        //上期余额
        List<BalanceDto> previousPeriod = lazyDao.findBySql("subject_balance_previous_period", Dict.create()
                .set("balanceTableName", balanceTableName)
                .set("accountSetsId", accountSetsId)
                .set("year", startDate.getYear())
                .set("period", startDate.getMonthValue()), BalanceDto.class);

        //生成月份等于启用月份，则期初等于启用月份
        if (enableMonth.equals(period)) {
            previousPeriod = this.loadInit(accountSetsId);
        }

        // 年初
        Map<CurrencyAuxiliary, BalanceDto> yearBegin = new HashMap<>();

        //过滤出年初字段
        CopyOptions copyOptions = CopyOptions.create().setPropertiesFilter((field, o) -> field.getName().startsWith("yearTotal"));

        for (BalanceDto balanceDto : previousPeriod) {
            if (period.getMonthValue() == 1 && !period.equals(enableMonth)) {
                //年末转下年年初
                balanceDto.endToBeginYear();
            }
            CurrencyAuxiliary currencyAuxiliary = new CurrencyAuxiliary(balanceDto.getSubjectId(), balanceDto.getCurrencyId(), balanceDto.getAuxiliaryDetailId());
            yearBegin.put(currencyAuxiliary, BeanUtil.toBean(balanceDto, BalanceDto.class, copyOptions));
        }

        Map<CurrencyAuxiliary, Optional<BalanceDto>> balanceDtoMap = previousPeriod.stream()
                .collect(Collectors.toMap(b -> new CurrencyAuxiliary(b.getSubjectId(), b.getCurrencyId(), b.getAuxiliaryDetailId()), Optional::of, (a, b) -> a));

        //本期变化金额
        List<BalanceDto> totalPeriod = lazyDao.findBySql("subject_balance_current", Dict.create()
                .set("cashFlow", FXY_FINANCIAL_BALANCE.equals(balanceTableName) ? null : 1)
                .set("carryForward", FXY_FINANCIAL_BALANCE_CASH_FLOW.equals(balanceTableName) ? 0 : null)
                .set("localCurrency", localCurrency.getId())
                .set("accountSetsId", accountSetsId)
                .set("year", period.getYear())
                .set("month", period.getMonthValue()), BalanceDto.class);
        //科目本币
        Map<CurrencyAuxiliary, Optional<BalanceDto>> optionalMap = getBalanceDtos(totalPeriod).stream()
                .collect(Collectors.groupingBy(b -> new CurrencyAuxiliary(b.getSubjectId(), localCurrency.getId(), 0L), Collectors.reducing(getBalanceDtoBinaryOperator())));

        this.mergedBegin(balanceDtoMap, optionalMap);

        //科目外币
        Map<CurrencyAuxiliary, Optional<BalanceDto>> optionalMap1 = getBalanceDtos(totalPeriod).stream()
                .filter(b -> !Objects.equals(b.getCurrencyId(), localCurrency.getId()))
                .collect(Collectors.groupingBy(b -> new CurrencyAuxiliary(b.getSubjectId(), b.getCurrencyId(), 0L), Collectors.reducing(getBalanceDtoBinaryOperator())));

        this.mergedBegin(balanceDtoMap, optionalMap1);

        //辅助科目外币
        Map<CurrencyAuxiliary, Optional<BalanceDto>> optionalMap2 = getBalanceDtos(totalPeriod).stream()
                .filter(b -> !Objects.equals(b.getCurrencyId(), localCurrency.getId()) && b.getAuxiliaryDetailId() != 0L)
                .collect(Collectors.groupingBy(b -> new CurrencyAuxiliary(b.getSubjectId(), b.getCurrencyId(), b.getAuxiliaryDetailId()), Collectors.reducing(getBalanceDtoBinaryOperator())));

        this.mergedBegin(balanceDtoMap, optionalMap2);

        //辅助科目本币
        Map<CurrencyAuxiliary, Optional<BalanceDto>> optionalMap3 = getBalanceDtos(totalPeriod).stream()
                .filter(b -> Objects.equals(b.getCurrencyId(), localCurrency.getId()) && b.getAuxiliaryDetailId() != 0L)
                .collect(Collectors.groupingBy(b -> new CurrencyAuxiliary(b.getSubjectId(), b.getCurrencyId(), b.getAuxiliaryDetailId()), Collectors.reducing(getBalanceDtoBinaryOperator())));

        this.mergedBegin(balanceDtoMap, optionalMap3);

        List<CurrencyAuxiliary> currencyAuxiliaries = balanceDtoMap.keySet().stream().sorted(Comparator.comparing(s -> localSubjectMap.get(s.getSubjectId()).getLevel())).toList();
        currencyAuxiliaries = CollUtil.reverse(ListUtil.toList(currencyAuxiliaries));
        Map<CurrencyAuxiliary, BalanceDto> caop = new HashMap<>();
        for (CurrencyAuxiliary k : currencyAuxiliaries) {
            BalanceDto balanceDto = caop.get(k);
            if (balanceDto == null) {
                Optional<BalanceDto> value = balanceDtoMap.get(k);
                balanceDto = BeanUtil.toBean(value.get(), BalanceDto.class);
                SubjectVo sb = localSubjectMap.get(balanceDto.getSubjectId());
                balanceDto.setBalanceDirection(sb.getBalanceDirection());
                balanceDto.calculateEnd();
                caop.put(k, balanceDto);
            }
            extracted(localSubjectMap, k, caop, balanceDto);
        }

        List<Balance> balances = new ArrayList<>();
        for (Map.Entry<CurrencyAuxiliary, BalanceDto> e : caop.entrySet()) {
            CurrencyAuxiliary k = e.getKey();
            BalanceDto value = e.getValue();
            Balance entity = balanceMapper.toEntity(value);
            entity.setAdjustPeriod(0);
            entity.setSubjectId(k.getSubjectId());
            entity.setCurrencyId(k.getCurrencyId());
            entity.setAuxiliaryDetailId(k.getAuxiliaryId());
            entity.setPeriod(period.getMonthValue());
            entity.setYear(period.getYear());
            entity.setDate(period.atDay(1));
            entity.setAccountSetsId(accountSetsId);
            entity.setId(snowflakeId.generate());
            balances.add(entity);
            balanceDtoMap.remove(k);
        }

        for (Map.Entry<CurrencyAuxiliary, Optional<BalanceDto>> entry : balanceDtoMap.entrySet()) {
            CurrencyAuxiliary key = entry.getKey();
            Optional<BalanceDto> val = entry.getValue();
            Balance entity = balanceMapper.toEntity(val.get());
            entity.setAdjustPeriod(0);
            entity.setSubjectId(key.getSubjectId());
            entity.setCurrencyId(key.getCurrencyId());
            entity.setAuxiliaryDetailId(key.getAuxiliaryId());
            entity.setPeriod(period.getMonthValue());
            entity.setYear(period.getYear());
            entity.setDate(period.atDay(1));
            entity.setAccountSetsId(accountSetsId);
            entity.setId(snowflakeId.generate());
            balances.add(entity);
        }

        balances = balances.stream().peek(b -> {
            CurrencyAuxiliary auxiliary = new CurrencyAuxiliary(b.getSubjectId(), b.getCurrencyId(), b.getAuxiliaryDetailId());
            if (yearBegin.containsKey(auxiliary)) {
                BalanceDto dto = yearBegin.get(auxiliary);
                //拷贝年初字段
                BeanUtil.copyProperties(dto, b, copyOptions);
            }
            b.calculateYearTotal();
        }).filter(Balance::isNotEmpty).toList();

        // 合并现金流底稿金额
        if (FXY_FINANCIAL_BALANCE_CASH_FLOW.equals(balanceTableName)) {
            List<ReportCashFlowDraft> cashFlowDrafts = bqf.selectFrom(qReportCashFlowDraft)
                    .where(qReportCashFlowDraft.accountSetsId.eq(accountSetsId)
                            .and(qReportCashFlowDraft.period.eq(period.atDay(1))))
                    .fetch();
            if (CollUtil.isNotEmpty(cashFlowDrafts)) {
                List<Balance> finalBalances = balances;
                List<Balance> refBalance = new ArrayList<>();
                cashFlowDrafts.parallelStream().forEach(draft -> {
                    // 草稿关联科目
                    Balance ref = CollUtil.findOne(finalBalances, balance -> Long.parseLong(draft.getFromTag()) == balance.getSubjectId() && balance.getAuxiliaryDetailId() == 0L);
                    if (ref == null) {
                        ref = new Balance();
                        ref.setAdjustPeriod(0);
                        ref.setSubjectId(Long.parseLong(draft.getFromTag()));
                        ref.setCurrencyId(localCurrency.getId());
                        ref.setAuxiliaryDetailId(0L);
                        ref.setPeriod(period.getMonthValue());
                        ref.setYear(period.getYear());
                        ref.setDate(period.atDay(1));
                        ref.setAccountSetsId(accountSetsId);
                        ref.setId(snowflakeId.generate());
                        ref.init();
                        refBalance.add(ref);
                    }
                    switch (AccessRules.valueOf(draft.getAccessRules())) {
                        case 发生额 -> {
                            SubjectVo subjectVo = localSubjectMap.get(Long.parseLong(draft.getFromTag()));
                            if ("借".equals(subjectVo.getBalanceDirection())) {
                                ref.setDebit(draft.getAmount());
                            } else {
                                ref.setCredit(draft.getAmount());
                            }
                        }
                        case 借方发生额 -> ref.setDebit(draft.getAmount());
                        case 贷方发生额 -> ref.setCredit(draft.getAmount());
                        case 余额 -> ref.setEndBalance(draft.getAmount());
                        case 期初余额 -> ref.setBeginBalance(draft.getAmount());
                        case 借方余额 -> ref.setEndDebitBalance(draft.getAmount());
                        case 贷方余额 -> ref.setEndCreditBalance(draft.getAmount());
                    }

                    ref.calculateYearTotal();
                });

                refBalance.addAll(balances);
                balances = refBalance;
            }
        }

        if (CollUtil.isNotEmpty(balances)) {
            lazyDao.batchUpdate(FXY_FINANCIAL_BALANCE.equals(balanceTableName) ? "updateBalance" : "updateBalanceCashFlow", balances);
        }
    }

    private List<BalanceDto> getBalanceDtos(List<BalanceDto> totalPeriod) {
        return JSON.parseArray(JSON.toJSONString(totalPeriod), BalanceDto.class);
    }

    private void extracted(Map<Long, SubjectVo> localSubjectMap, CurrencyAuxiliary k, Map<CurrencyAuxiliary, BalanceDto> balanceDtoMap, BalanceDto balanceDto) {
        //科目信息
        SubjectVo subjectVo = localSubjectMap.get(k.getSubjectId());
        //判断是否有上级
        if (subjectVo.getParentId() != null && subjectVo.getParentId() != 0L) {
            CurrencyAuxiliary currencyAuxiliary = new CurrencyAuxiliary(subjectVo.getParentId(), k.currencyId, k.auxiliaryId);
            //获取上级的科目信息
            SubjectVo parentSubject = localSubjectMap.get(currencyAuxiliary.getSubjectId());

            BalanceDto dto = new BalanceDto();
            dto.setSubjectId(parentSubject.getId());
            dto.setBalanceDirection(parentSubject.getBalanceDirection());
            dto.setSubjectCode(parentSubject.getCode());
            dto.setCurrencyId(currencyAuxiliary.getCurrencyId());
            dto.setAuxiliaryDetailId(currencyAuxiliary.getAuxiliaryId());
            dto.setDate(balanceDto.getDate());
            dto.setPeriod(balanceDto.getPeriod());
            dto.setYear(balanceDto.getYear());
            dto.setSubjectName(parentSubject.getName());
            dto.setSubjectFullName(parentSubject.getFullName());

            for (SubjectVo s : parentSubject.getChildren()) {//子科目
                BalanceDto childrenDto = balanceDtoMap.get(new CurrencyAuxiliary(s.getId(), k.currencyId, k.auxiliaryId));
                if (childrenDto != null) {
                    dto.summary(childrenDto);
                    dto.calculateBeginBalance(childrenDto);
                }
            }
            //计算期末金额
            dto.calculateEnd();

            balanceDtoMap.put(currencyAuxiliary, dto);

            this.extracted(localSubjectMap, currencyAuxiliary, balanceDtoMap, dto);
        }
    }

    private void mergedBegin(Map<CurrencyAuxiliary, Optional<BalanceDto>> balanceDtoMap, Map<CurrencyAuxiliary, Optional<BalanceDto>> optionalMap) {
        optionalMap.forEach((key, val) -> {
            Optional<BalanceDto> balanceDto = balanceDtoMap.get(key);
            if (balanceDto != null) {
                balanceDto.ifPresent(b -> {
                    b.setCurrentPeriod(val.get());
                });
            } else {
                BalanceDto dto = BeanUtil.toBean(val.get(), BalanceDto.class);
                balanceDtoMap.put(key, Optional.of(dto));
            }
        });
    }

    private BinaryOperator<BalanceDto> getBalanceDtoBinaryOperator() {
        return (a, b) -> {
            a.add(b);
            return a;
        };
    }

    /**
     * 还原期初
     *
     * @param accountSetsId
     */
    private List<BalanceDto> loadInit(Long accountSetsId) {
        return jqf.selectFrom(qBalanceInitial)
                .select(qBalanceInitial, QSubject.subject.balanceDirection)
                .innerJoin(QSubject.subject).on(qBalanceInitial.subjectId.eq(QSubject.subject.id).and(QSubject.subject.accountSetsId.eq(qBalanceInitial.accountSetsId)))
                .where(qBalanceInitial.accountSetsId.eq(accountSetsId))
                .fetch().stream().map(tuple -> {
                    BalanceInitial bi = tuple.get(qBalanceInitial);
                    BalanceDto bean = BeanUtil.toBean(bi, BalanceDto.class);
                    bean.setEndDebitQuantity(bi.getDebitQuantity());
                    bean.setEndCreditQuantity(bi.getCreditQuantity());
                    Field[] fields = ReflectUtil.getFields(BalanceDto.class, s -> s.getType().equals(BigDecimal.class));
                    Arrays.stream(fields).forEach(field -> {
                        BigDecimal val = (BigDecimal) ReflectUtil.getFieldValue(bean, field);
                        if (val == null) {
                            ReflectUtil.setFieldValue(bean, field, BigDecimal.ZERO);
                        }
                    });
                    bean.setDebitQuantity(BigDecimal.ZERO);
                    bean.setCreditQuantity(BigDecimal.ZERO);
                    if ("借".equals(tuple.get(QSubject.subject.balanceDirection))) {
                        bean.setBeginDebitBalance(bean.getBeginBalance());
                        bean.setEndDebitBalance(bean.getBeginBalance());
                        bean.setYearTotalBeginDebit(bean.getYearTotal());
                        // 贷方取负数
                        bean.setBeginCreditBalance(NumberUtil.sub(BigDecimal.ZERO, bean.getBeginBalance()));
                        bean.setEndCreditBalance(NumberUtil.sub(BigDecimal.ZERO, bean.getBeginBalance()));
                        bean.setYearTotalBeginCredit(NumberUtil.sub(BigDecimal.ZERO, bean.getYearTotal()));
                    } else {
                        bean.setBeginCreditBalance(bean.getBeginBalance());
                        bean.setEndCreditBalance(bean.getBeginBalance());
                        bean.setYearTotalBeginCredit(bean.getYearTotal());
                        // 借方取负数
                        bean.setBeginDebitBalance(NumberUtil.sub(BigDecimal.ZERO, bean.getBeginBalance()));
                        bean.setEndDebitBalance(NumberUtil.sub(BigDecimal.ZERO, bean.getBeginBalance()));
                        bean.setYearTotalBeginDebit(NumberUtil.sub(BigDecimal.ZERO, bean.getYearTotal()));
                    }
                    return bean;
                }).toList();
    }

    //开启事务,传入隔离级别
    private TransactionStatus beginTransaction() {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        // 事物隔离级别，开启新事务 TransactionDefinition.ISOLATION_READ_COMMITTED
        def.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        // 事务传播行为
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        //将拿到的事务返回进去，才能提交。
        return transactionManager.getTransaction(def);
    }

    @Data
    @ToString
    @AllArgsConstructor
    public static class CurrencyAuxiliary {
        private Long subjectId;
        private Long currencyId;
        private Long auxiliaryId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CurrencyAuxiliary that = (CurrencyAuxiliary) o;
            return Objects.equals(subjectId, that.subjectId) && Objects.equals(currencyId, that.currencyId) && Objects.equals(auxiliaryId, that.auxiliaryId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(subjectId, currencyId, auxiliaryId);
        }
    }
}
