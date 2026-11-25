package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.model.dto.ProfitCheckDto;
import cn.gson.financial.kernel.model.dto.SubjectDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.SubjectCategory;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.CheckoutRepository;
import cn.gson.financial.kernel.model.vo.SubjectVo;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.alibaba.fastjson.JSON;
import com.querydsl.core.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

import static org.springframework.transaction.annotation.Isolation.READ_UNCOMMITTED;

/**
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : ${PACKAGE_NAME}</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年08月23日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CheckoutService extends AbsService implements AccountSetsProcessor, BackupProcessor {
    private final CheckoutRepository checkoutRepository;
    private final QCheckout qCheckout = QCheckout.checkout;
    private final QAuxiliaryDetail qAuxiliaryDetail = QAuxiliaryDetail.auxiliaryDetail;
    private final QVoucher qVoucher = QVoucher.voucher;
    private final QBalance qBalance = QBalance.balance;
    private final QSubject qSubject = QSubject.subject;

    private final BalanceCalcService balanceCalcService;

    private final SubjectService subjectService;

    /**
     * 断号检查
     *
     * @param accountSetsId
     * @param year
     * @param month
     * @return
     */
    public boolean brokenCheck(Long accountSetsId, Integer year, Integer month) {
        List<String> wordList = bqf.selectFrom(qVoucher)
                .select(qVoucher.word)
                .where(qVoucher.accountSetsId.eq(accountSetsId).and(qVoucher.year.eq(year)).and(qVoucher.month.eq(month)))
                .groupBy(qVoucher.word).fetch();
        for (String word : wordList) {
            List<Integer> codeList = bqf.selectFrom(qVoucher)
                    .select(qVoucher.code)
                    .where(qVoucher.accountSetsId.eq(accountSetsId)
                            .and(qVoucher.word.eq(word))
                            .and(qVoucher.year.eq(year)).and(qVoucher.month.eq(month)))
                    .orderBy(qVoucher.code.asc())
                    .fetch();
            //起点必须是1
            if (codeList.get(0) != 1) {
                return false;
            }
            //大于1，继续检测后续的code
            if (codeList.size() > 1) {
                for (int i = 1; i < codeList.size(); i++) {
                    //判断前后是否连续
                    if (codeList.get(i) - codeList.get(i - 1) != 1) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * 结账
     *
     * @param accountSets
     * @param year
     * @param month
     * @return
     */
    @Transactional
    public void invoicing(AccountSets accountSets, Integer year, Integer month) {
        LocalDate targetDate = LocalDate.of(year, month, 1);
        LocalDate lastCompletedCheckDate = this.lastCompletedCheckDate(accountSets.getId());
        
        // 检查目标月份是否存在且未结账（只要检测通过即可结账，不限制是否已结转损益）
        Checkout targetCheckout = bqf.selectFrom(qCheckout)
                .where(qCheckout.accountSetsId.eq(accountSets.getId())
                        .and(qCheckout.checkYear.eq(year))
                        .and(qCheckout.checkMonth.eq(month)))
                .fetchFirst();
        
        Assert.isTrue(targetCheckout != null, "该期间不存在，无法结账~");
        Assert.isTrue(targetCheckout.getStatus() != 2, "该期间已结账，无需重复结账~");
        
        // 如果目标月份之前有未结账的月份，需要先自动结账这些月份（只要检测通过即可，不限制是否已结转损益）
        LocalDate startDate = lastCompletedCheckDate != null ? lastCompletedCheckDate.plusMonths(1) : accountSets.getEnableDate().withDayOfMonth(1);
        
        if (targetDate.isAfter(startDate)) {
            // 查询从最后一个已结账月份的下一个月到目标月份之间的所有未结账的月份（status=0或1）
            List<Checkout> checkoutsToCheck = bqf.selectFrom(qCheckout)
                    .where(qCheckout.accountSetsId.eq(accountSets.getId())
                            .and(qCheckout.status.ne(2)) // 未结账的月份（status=0或1）
                            .and(qCheckout.checkDate.goe(startDate))
                            .and(qCheckout.checkDate.before(targetDate)))
                    .orderBy(qCheckout.checkDate.asc())
                    .fetch();
            
            // 自动结账这些月份
            for (Checkout checkout : checkoutsToCheck) {
                jqf.update(qCheckout)
                        .set(qCheckout.status, 2)
                        .set(qCheckout.checkDate, checkout.getCheckDate())
                        .where(qCheckout.accountSetsId.eq(accountSets.getId())
                                .and(qCheckout.checkYear.eq(checkout.getCheckYear()))
                                .and(qCheckout.checkMonth.eq(checkout.getCheckMonth())))
                        .execute();
                
                // 锁定该月份的凭证
                jqf.update(qVoucher)
                        .set(qVoucher.locked, true)
                        .where(qVoucher.accountSetsId.eq(accountSets.getId())
                                .and(qVoucher.year.eq(checkout.getCheckYear()))
                                .and(qVoucher.month.eq(checkout.getCheckMonth())))
                        .execute();
                
                // 计算该月份的余额（确保资产负债表有数据）
                balanceCalcService.calc(accountSets.getId(), YearMonth.from(checkout.getCheckDate()));
                
                // 计算该月份的下一个期间的余额（确保下一个期间的报表有数据，即使还没有凭证，也要有期初余额）
                LocalDate checkoutNextMonth = checkout.getCheckDate().plusMonths(1);
                balanceCalcService.calc(accountSets.getId(), YearMonth.from(checkoutNextMonth));
            }
        }

        // 结账目标月份
        jqf.update(qCheckout)
                .set(qCheckout.status, 2)
                .set(qCheckout.checkDate, targetDate)
                .where(qCheckout.accountSetsId.eq(accountSets.getId())
                        .and(qCheckout.checkYear.eq(year))
                        .and(qCheckout.checkMonth.eq(month)))
                .execute();

        // 创建下一个期间的结账记录（如果不存在）
        LocalDate nextMonth = targetDate.plusMonths(1);
        Checkout nextCheckout = bqf.selectFrom(qCheckout)
                .where(qCheckout.accountSetsId.eq(accountSets.getId())
                        .and(qCheckout.checkYear.eq(nextMonth.getYear()))
                        .and(qCheckout.checkMonth.eq(nextMonth.getMonthValue())))
                .fetchFirst();
        if (nextCheckout == null) {
            Checkout checkout = new Checkout();
            checkout.setStatus(0);
            checkout.setAccountSetsId(accountSets.getId());
            checkout.setCheckYear(nextMonth.getYear());
            checkout.setCheckMonth(nextMonth.getMonthValue());
            checkout.setCheckDate(nextMonth);
            this.save(checkout);
        }

        //更新当前期间为下一个期间
        jqf.update(QAccountSets.accountSets)
                .set(QAccountSets.accountSets.currentAccountDate, nextMonth)
                .where(QAccountSets.accountSets.id.eq(accountSets.getId()))
                .execute();

        // 锁定目标月份的凭证
        jqf.update(qVoucher)
                .set(qVoucher.locked, true)
                .where(qVoucher.accountSetsId.eq(accountSets.getId())
                        .and(qVoucher.year.eq(year))
                        .and(qVoucher.month.eq(month)))
                .execute();
        
        // 计算目标月份的余额（确保资产负债表有数据）
        balanceCalcService.calc(accountSets.getId(), YearMonth.from(targetDate));
        
        // 计算下一个期间的余额（确保下一个期间的报表有数据，即使还没有凭证，也要有期初余额）
        // 由于calc方法会基于maxVoucherDate计算endCheckMonth，如果下一个期间还没有凭证，可能不会计算
        // 所以我们需要直接计算下一个期间，即使它还没有凭证，也要生成期初余额
        // 通过传入nextMonth作为period，如果nextMonth在endCheckMonth之后，calc方法会交换并计算
        balanceCalcService.calc(accountSets.getId(), YearMonth.from(nextMonth));
    }

    /**
     * 反结账
     *
     * @param accountSets
     * @param year
     * @param month
     * @return
     */
    @Transactional
    public boolean unCheck(AccountSets accountSets, Integer year, Integer month) {
        LocalDate targetDate = LocalDate.of(year, month, 1);
        
        // 检查目标月份是否已结账
        Checkout targetCheckout = bqf.selectFrom(qCheckout)
                .where(qCheckout.accountSetsId.eq(accountSets.getId())
                        .and(qCheckout.checkYear.eq(year))
                        .and(qCheckout.checkMonth.eq(month))
                        .and(qCheckout.status.eq(2)))
                .fetchFirst();
        
        Assert.isTrue(targetCheckout != null, "该期间未结账，不允许反结账~");

        // 如果反结账的不是最后一个月份，需要将后续已结账的月份也反结账
        LocalDate lastCompletedCheckDate = this.lastCompletedCheckDate(accountSets.getId());
        if (lastCompletedCheckDate != null && lastCompletedCheckDate.isAfter(targetDate)) {
            // 反结账目标月份及之后的所有已结账月份
            // 查询所有需要反结账的月份（从目标月份到最后一个已结账月份之间的所有已结账月份）
            List<Checkout> checkoutsToUncheck = bqf.selectFrom(qCheckout)
                    .where(qCheckout.accountSetsId.eq(accountSets.getId())
                            .and(qCheckout.status.eq(2))
                            .and(qCheckout.checkDate.goe(targetDate))
                            .and(qCheckout.checkDate.loe(lastCompletedCheckDate)))
                    .orderBy(qCheckout.checkDate.asc())
                    .fetch();
            
            for (Checkout checkout : checkoutsToUncheck) {
                // 解锁该月份的凭证
                jqf.update(qVoucher)
                        .set(qVoucher.locked, false)
                        .where(qVoucher.accountSetsId.eq(accountSets.getId())
                                .and(qVoucher.year.eq(checkout.getCheckYear()))
                                .and(qVoucher.month.eq(checkout.getCheckMonth())))
                        .execute();
                
                // 更新结账状态为已结转损益、未结账
                jqf.update(qCheckout)
                        .set(qCheckout.status, 1)
                        .where(qCheckout.accountSetsId.eq(accountSets.getId())
                                .and(qCheckout.status.eq(2))
                                .and(qCheckout.checkYear.eq(checkout.getCheckYear()))
                                .and(qCheckout.checkMonth.eq(checkout.getCheckMonth())))
                        .execute();
            }
        } else {
            // 只反结账目标月份
            jqf.update(qVoucher)
                    .set(qVoucher.locked, false)
                    .where(qVoucher.accountSetsId.eq(accountSets.getId())
                            .and(qVoucher.year.eq(year))
                            .and(qVoucher.month.eq(month)))
                    .execute();
            
            jqf.update(qCheckout)
                    .set(qCheckout.status, 1)
                    .where(qCheckout.accountSetsId.eq(accountSets.getId())
                            .and(qCheckout.status.eq(2))
                            .and(qCheckout.checkYear.eq(year))
                            .and(qCheckout.checkMonth.eq(month)))
                    .execute();
        }

        //更新当前期间为反结账期间
        jqf.update(QAccountSets.accountSets)
                .set(QAccountSets.accountSets.currentAccountDate, targetDate)
                .where(QAccountSets.accountSets.id.eq(accountSets.getId()))
                .execute();

        // 反结账后不调用reCreateCheckout，避免重新创建时错误恢复结账状态
        // 特别是对于没有凭证的月份，reCreateCheckout可能会错误地将其状态恢复为已结账
        // this.reCreateCheckout(accountSets);

        return true;
    }

    public List<Checkout> list(Long accountSetsId) {
        return bqf.selectFrom(qCheckout)
                .where(qCheckout.accountSetsId.eq(accountSetsId))
                .orderBy(qCheckout.checkYear.desc(), qCheckout.checkMonth.desc())
                .fetch();
    }

    /**
     * 账期重置（默认同时根据凭证重新计算当前记账日期）
     *
     * @param accountSets 账套
     */
    @Transactional
    public void reCreateCheckout(AccountSets accountSets) {
        this.reCreateCheckout(accountSets, true);
    }

    /**
     * 账期重置
     *
     * @param accountSets        账套
     * @param adjustCurrentDate  是否根据凭证最大期间调整当前记账日期
     */
    @Transactional
    public void reCreateCheckout(AccountSets accountSets, boolean adjustCurrentDate) {
        LocalDate maxVoucherDate = bqf.selectFrom(qVoucher)
                .select(qVoucher.voucherDate.max())
                .where(qVoucher.accountSetsId.eq(accountSets.getId()))
                .orderBy(qVoucher.voucherDate.asc())
                .fetchFirst();

        // FN-FIX-RECREATE-CHECKOUT-PRESERVE-NEXT-PERIOD：把"下个账期恒在"钉成不变量。
        // 不变量：[启用月 .. 重建上界] 每月必有一行 Checkout（早于最后结账月=status2，否则 status<2）；
        // currentAccountDate 绝不指向不存在的 Checkout。
        // 重建上界 endBound = max(最大凭证月, 最后已结账月+1, [仅非 adjust 模式]当前记账月)，且不早于启用月。
        boolean hasVouchers = maxVoucherDate != null;
        // 删除范围：有凭证→只删未结账（保留 status=2）；无凭证→全重置删全部（保持原语义）
        if (hasVouchers) {
            jqf.delete(qCheckout)
                    .where(qCheckout.accountSetsId.eq(accountSets.getId()).and(qCheckout.status.lt(2)))
                    .execute();
        } else {
            jqf.delete(qCheckout)
                    .where(qCheckout.accountSetsId.eq(accountSets.getId()))
                    .execute();
        }

        // 最后一个已结账月（status=2）；无凭证/无结账时为 null（删除后再查，存活的都是 status=2）
        LocalDate lastCheckDate = bqf.selectFrom(qCheckout)
                .select(qCheckout.checkDate)
                .where(qCheckout.accountSetsId.eq(accountSets.getId()).and(qCheckout.status.eq(2)))
                .orderBy(qCheckout.checkDate.desc(), qCheckout.id.desc())
                .fetchFirst();

        YearMonth enableYm = YearMonth.from(accountSets.getEnableDate());
        YearMonth endBound = computeRebuildEndBound(
                enableYm,
                hasVouchers ? YearMonth.from(maxVoucherDate) : null,
                lastCheckDate != null ? YearMonth.from(lastCheckDate) : null,
                accountSets.getCurrentAccountDate() != null ? YearMonth.from(accountSets.getCurrentAccountDate()) : null,
                adjustCurrentDate);

        // 从启用月铺到上界，缺的补行：早于最后结账月→status=2，否则 status=0；已存在且早于结账月的升 2（不误降级 status=2）
        LocalDate checkDate = enableYm.atDay(1);
        LocalDate endDate = endBound.atDay(1);
        while (!checkDate.isAfter(endDate)) {
            Checkout current = bqf.selectFrom(qCheckout)
                    .where(qCheckout.accountSetsId.eq(accountSets.getId()).and(qCheckout.checkDate.eq(checkDate)))
                    .fetchFirst();
            if (current == null) {
                initCheck(checkDate, lastCheckDate != null && lastCheckDate.isAfter(checkDate) ? 2 : 0, accountSets);
            } else if (lastCheckDate != null && current.getCheckDate().isBefore(lastCheckDate)) {
                current.setStatus(2);
                checkoutRepository.save(current);
            }
            checkDate = checkDate.plusMonths(1);
        }

        // adjust 模式才重算并下发 currentAccountDate（到重建上界，保证落在已存在的 Checkout 上）；
        // 非 adjust 模式（凭证增删改）不动 currentAccountDate —— 它已被纳入 endBound、对应行已重建，不会悬空。
        if (adjustCurrentDate) {
            accountSets.setCurrentAccountDate(endDate);
            jqf.update(QAccountSets.accountSets)
                    .set(QAccountSets.accountSets.currentAccountDate, endDate)
                    .where(QAccountSets.accountSets.id.eq(accountSets.getId()))
                    .execute();
        }
    }

    /**
     * FN-FIX-RECREATE-CHECKOUT-PRESERVE-NEXT-PERIOD：计算账期重建上界（纯逻辑，包级可见便于单测）。
     *
     * <p>{@code endBound = max(启用月, 最大凭证月, 最后已结账月+1, [仅非 adjust 模式]当前记账月)}。
     * <ul>
     *   <li>最后已结账月+1：把"关账后下个账期恒在"钉成不变量（不再依赖"最后结账月==最大凭证月"的窄 +1 规则）。</li>
     *   <li>当前记账月（仅非 adjust 模式）：凭证增删改触发 reCreateCheckout(false) 时 currentAccountDate 不会被更新，
     *       重建必须覆盖它，否则它悬空指向不存在的 Checkout。adjust 模式下 currentAccountDate 会被重算到 endBound，无需纳入。</li>
     * </ul>
     *
     * @param enableYm          启用月（下界）
     * @param maxVoucherYm      最大凭证月，可空（无凭证时）
     * @param lastClosedYm      最后已结账月，可空（无已结账月时）
     * @param currentYm         当前记账月，可空
     * @param adjustCurrentDate 是否 adjust 模式（true=显式重置会重算 currentAccountDate）
     */
    static YearMonth computeRebuildEndBound(YearMonth enableYm, YearMonth maxVoucherYm,
                                            YearMonth lastClosedYm, YearMonth currentYm, boolean adjustCurrentDate) {
        YearMonth endBound = enableYm;
        if (maxVoucherYm != null && maxVoucherYm.isAfter(endBound)) {
            endBound = maxVoucherYm;
        }
        if (lastClosedYm != null) {
            YearMonth afterClosed = lastClosedYm.plusMonths(1);
            if (afterClosed.isAfter(endBound)) {
                endBound = afterClosed;
            }
        }
        if (!adjustCurrentDate && currentYm != null && currentYm.isAfter(endBound)) {
            endBound = currentYm;
        }
        return endBound;
    }

    private void initCheck(LocalDate accountSets, int status, AccountSets accountSets1) {
        Checkout checkout = new Checkout();
        checkout.setCheckYear(accountSets.getYear());
        checkout.setCheckMonth(accountSets.getMonthValue());
        checkout.setStatus(status);
        checkout.setCheckDate(accountSets);
        checkout.setAccountSetsId(accountSets1.getId());
        checkoutRepository.save(checkout);
    }

    /**
     * 首个未结账期间
     *
     * @param accountSetsId
     * @return
     */
    public LocalDate lastUnCheckDate(Long accountSetsId) {
        return bqf.selectFrom(qCheckout)
                .select(qCheckout.checkDate)
                .where(qCheckout.accountSetsId.eq(accountSetsId).and(qCheckout.status.ne(2)))
                .orderBy(qCheckout.checkDate.asc(), qCheckout.id.asc())
                .fetchFirst();
    }

    /**
     * 最近一个已结账日期
     *
     * @param accountSetsId
     * @return
     */
    public LocalDate lastCompletedCheckDate(Long accountSetsId) {
        return bqf.selectFrom(qCheckout)
                .select(qCheckout.checkDate)
                .where(qCheckout.accountSetsId.eq(accountSetsId).and(qCheckout.status.eq(2)))
                .orderBy(qCheckout.checkDate.desc(), qCheckout.id.desc())
                .fetchFirst();
    }

    @Transactional(isolation = READ_UNCOMMITTED)
    public Checkout save(Checkout checkout) {
        if (checkout.getId() != null) {
            Checkout original = checkoutRepository.getReferenceById(checkout.getPrimaryKey());
            BeanUtil.copyProperties(checkout, original, CopyOptions.create().ignoreNullValue());
            checkout = checkoutRepository.save(original);
        } else {
            checkout.setStatus(0);
            checkout = checkoutRepository.save(checkout);
            balanceCalcService.calc(checkout.getAccountSetsId(), YearMonth.from(checkout.getCheckDate()));
        }
        return checkout;
    }

    @Transactional
    public void delete(PrimaryKey primaryKey) {
        this.delete(checkoutRepository.getReferenceById(primaryKey));
    }

    @Transactional
    @Override
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        //初始化结转状态
        Checkout checkout = new Checkout();
        checkout.setAccountSetsId(accountSets.getId());
        checkout.setCheckYear(accountSets.getEnableDate().getYear());
        checkout.setCheckMonth(accountSets.getEnableDate().getMonthValue());
        checkout.setCheckDate(accountSets.getEnableDate());
        checkout.setStatus(0);
        checkoutRepository.save(checkout);
    }

    @Transactional
    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qCheckout)
                .where(qCheckout.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qCheckout)
                .where(qCheckout.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<?> backupType() {
        return Checkout.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {
        List<Checkout> entityList = JSON.parseArray(json, Checkout.class);
        for (Checkout entity : entityList) {
            entity.setAccountSetsId(accountSetsId);
            checkoutRepository.save(entity);
        }
    }

    @Transactional
    public void delete(Checkout c) {
        checkoutRepository.deleteById(c.getPrimaryKey());
        //删除期间余额数据
        jqf.delete(qBalance)
                .where(qBalance.accountSetsId.eq(c.getAccountSetsId())
                        .and(qBalance.year.eq(c.getCheckYear()))
                        .and(qBalance.period.eq(c.getCheckMonth())))
                .execute();
    }

    /**
     * 损益检查
     *
     * @param accountSetsId
     * @param year
     * @param month
     * @return
     */
    public List<ProfitCheckDto> profitCheck(Long accountSetsId, Integer year, Integer month) {
        // FN-FIX-PROFIT-CARRY-AUTO：按 code 前缀 5/6 + leaf=true 加载全部损益类末级科目
        // （小微 5xxx / 企业准则 6xxx），替代原本「先查 SubjectCategory.INCOME，空才退到 EXPENSES」
        // 的单类别加载 —— 那种实现会让另一类别的损益科目（例如所得税费用 5801 被建到「费用」类）
        // 永远不被结转，导致资产负债表不平。
        // FN-FIX-AUX-NULL-VS-ZERO：把"无辅助"余额的 NULL 与 0 当成同一个桶。
        //  - WHERE 用 COALESCE(auxiliaryDetailId,0)=0（即 eq(0) OR isNull）合并 NULL/0 两条写入路径
        //    （原生 save 落 NULL、openapi 落 0）产生的两行；真实辅助桶（aux≠0）不受影响（NULL≠0 在 SQL 为 false）。
        //  - 同步把 credit/debit 也 SUM、groupBy 只按科目：否则同一科目的 NULL 行(借4000)与 0 行(贷4000)
        //    会因 (credit,debit) 不同被拆成两组、各自非零 → 假报 LOSS。SUM 后合成一行 net=0。
        List<Tuple> tuples = bqf.selectFrom(qBalance)
                .select(qBalance.endBalance.sum(), qSubject, qBalance.credit.sum(), qBalance.debit.sum(), qBalance.subjectId)
                .join(qSubject).on(qSubject.id.eq(qBalance.subjectId).and(qSubject.accountSetsId.eq(qBalance.accountSetsId)))
                .where(qBalance.accountSetsId.eq(accountSetsId)
                        .and(qBalance.auxiliaryDetailId.eq(0L).or(qBalance.auxiliaryDetailId.isNull()))
                        .and(qSubject.leaf.isTrue())
                        .and(qSubject.code.startsWith("5").or(qSubject.code.startsWith("6")))
                        .and(qBalance.year.eq(year))
                        .and(qBalance.period.eq(month)))
                .groupBy(qSubject, qBalance.subjectId)
                .orderBy(qSubject.queryCode.asc())
                .fetch();
        List<ProfitCheckDto> subjectList = new ArrayList<>();
        for (Tuple tuple : tuples) {
            BigDecimal balance = tuple.get(qBalance.endBalance.sum());
            SubjectDto subjectDto = BeanUtil.toBean(tuple.get(qSubject), SubjectDto.class);
            BigDecimal subTotal = BigDecimal.ZERO;
            if (BigDecimal.ZERO.compareTo(balance) != 0) {
                if (CollUtil.isNotEmpty(subjectDto.getAuxiliaryAccounting())) {
                    List<Balance> balances = bqf.selectFrom(qBalance)
                            .where(qBalance.accountSetsId.eq(accountSetsId)
                                    .and(qBalance.subjectId.eq(subjectDto.getId()))
                                    .and(qBalance.auxiliaryDetailId.ne(0L))
                                    .and(qBalance.year.eq(year))
                                    .and(qBalance.endBalance.ne(BigDecimal.ZERO))
                                    .and(qBalance.period.eq(month))
                            ).fetch();
                    if (CollUtil.isNotEmpty(balances)) {
                        List<AuxiliaryDetail> auxiliaryDetailList = bqf.selectFrom(qAuxiliaryDetail)
                                .where(qAuxiliaryDetail.accountSetsId.eq(accountSetsId)
                                        .and(qAuxiliaryDetail.id.in(balances.stream().map(Balance::getAuxiliaryDetailId).toList())))
                                .fetch();

                        Map<Long, AuxiliaryDetail> detailMap =
                                auxiliaryDetailList.stream().collect(Collectors.toMap(AuxiliaryDetail::getId, d -> d));

                        for (Balance b : balances) {
                            ProfitCheckDto dto = new ProfitCheckDto();
                            dto.setBalance(b.getEndBalance());
                            dto.setFcAmount(new BigDecimal("0"));
                            dto.setSubjectId(tuple.get(qBalance.subjectId));
                            dto.setSubject(subjectDto);
                            dto.setCode(subjectDto.getCode());
                            dto.setFullName(subjectDto.getFullName());
                            // FN-FIX-PL-CARRY-VERIFY-ONLY：透出 auxiliaryDetailId 给 xiaowei 作 pl_carry 维度 key
                            dto.setAuxiliaryDetailId(b.getAuxiliaryDetailId());
                            if (detailMap.get(b.getAuxiliaryDetailId()) != null) {
                                dto.setAuxiliary(detailMap.get(b.getAuxiliaryDetailId()).getDetails().stream().map(d ->
                                                SpringUtil.getBean(d.getCategoryType().dataService).
                                                        loadById(accountSetsId, d.getCategoryId(), d.getValue()))
                                        .filter(Optional::isPresent).map(Optional::get).toList());
                            }
                            dto.setBalanceDirection(subjectDto.getBalanceDirection());

                            if ("借".equals(subjectDto.getBalanceDirection())) {
                                dto.setFcAmount(NumberUtil.sub(b.getDebit(), b.getCredit()));
                            } else {
                                dto.setFcAmount(NumberUtil.sub(b.getCredit(), b.getDebit()));
                            }
                            subjectList.add(dto);
                            //合计辅助核算
                            subTotal = NumberUtil.add(subTotal, dto.getFcAmount());
                        }

                        if (NumberUtil.equals(balance, subTotal)) {
                            continue;
                        }
                        // 辅助项非必填的情况，辅助项金额不足以覆盖全部金额，继续添加科目剩余金额
                        balance = NumberUtil.sub(balance, subTotal);
                    }
                }

                ProfitCheckDto dto = new ProfitCheckDto();
                dto.setBalance(balance);
                dto.setFcAmount(new BigDecimal("0"));
                dto.setSubjectId(tuple.get(qBalance.subjectId));
                dto.setSubject(subjectDto);
                dto.setCode(subjectDto.getCode());
                dto.setFullName(subjectDto.getFullName());
                dto.setBalanceDirection(subjectDto.getBalanceDirection());
                // FN-FIX-PL-CARRY-VERIFY-ONLY：主科目（非辅助拆分）行 aux 维度 ID 用 0
                dto.setAuxiliaryDetailId(0L);

                if ("借".equals(subjectDto.getBalanceDirection())) {
                    dto.setFcAmount(NumberUtil.sub(tuple.get(qBalance.debit.sum()), tuple.get(qBalance.credit.sum())));
                } else {
                    dto.setFcAmount(NumberUtil.sub(tuple.get(qBalance.credit.sum()), tuple.get(qBalance.debit.sum())));
                }
                subjectList.add(dto);
            }
        }

        return subjectList;
    }

    /**
     * 变更状态
     *
     * @param accountSetsId
     * @param year
     * @param month
     * @param status
     */
    @Transactional
    public void updateStatus(Long accountSetsId, Integer year, Integer month, int status) {
        jqf.update(qCheckout)
                .set(qCheckout.status, status)
                .where(qCheckout.accountSetsId.eq(accountSetsId)
                        .and(qCheckout.checkYear.eq(year))
                        .and(qCheckout.checkMonth.eq(month)))
                .execute();
    }

    /**
     * 判断是否有未审核凭证
     *
     * @param accountSetsId
     * @param year
     * @param month
     * @return
     */
    public boolean reviewedCheck(Long accountSetsId, Integer year, Integer month) {
        return bqf.selectFrom(qVoucher)
                .where(qVoucher.accountSetsId.eq(accountSetsId)
                        .and(qVoucher.month.eq(month))
                        .and(qVoucher.year.eq(year))
                        .and(qVoucher.auditMemberId.isNull()))
                .fetchCount() == 0;
    }

    /**
     * 启用期间是否已结账
     *
     * @param accountSets
     * @return
     */
    public boolean beginCheck(AccountSets accountSets) {
        return bqf.selectFrom(qCheckout)
                .where(qCheckout.accountSetsId.eq(accountSets.getId())
                        .and(qCheckout.checkMonth.eq(accountSets.getEnableDate().getMonthValue()))
                        .and(qCheckout.checkYear.eq(accountSets.getEnableDate().getYear()))
                        .and(qCheckout.status.eq(2)))
                .fetchCount() == 1;
    }

    public LocalDate last(AccountSets accountSets) {
        LocalDate lastCheckDate = bqf.selectFrom(qCheckout)
                .select(qCheckout.checkDate)
                .where(qCheckout.accountSetsId.eq(accountSets.getId()).and(qCheckout.status.ne(2)))
                .orderBy(qCheckout.checkDate.asc(), qCheckout.id.asc())
                .fetchFirst();
        return Optional.ofNullable(lastCheckDate).orElse(accountSets.getEnableDate().withDayOfMonth(1));
    }

    public Boolean selectBankStatement(Long accountSetsId, String currentAccountDate, String type) {
        Map<String, Object> params = new HashMap<>();
        params.put("accountSetsId", accountSetsId);
        params.put("currentAccountDate", currentAccountDate);
        params.put("type", type);
        List<FileInfo> fileInfo = lazyDao.findBySql("selectBankStatement", params, FileInfo.class);
        if (CollUtil.isEmpty(fileInfo)) {
            return false;
        } else {
            return true;
        }
    }
}

