package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.config.SubjectUseCheckProcessor;
import cn.gson.financial.kernel.model.dto.BalanceDto;
import cn.gson.financial.kernel.model.dto.CurrencyDto;
import cn.gson.financial.kernel.model.dto.SubjectBalanceForm;
import cn.gson.financial.kernel.model.dto.TrialBalanceDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.entity.AuxiliaryDetail.Detail;
import cn.gson.financial.kernel.model.mapper.BalanceMapper;
import cn.gson.financial.kernel.model.repo.AuxiliaryDetailRepository;
import cn.gson.financial.kernel.model.repo.BalanceInitialRepository;
import cn.gson.financial.kernel.model.repo.BalanceRepository;
import cn.gson.financial.kernel.model.vo.BalanceAuxiliaryForm;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年03月15日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BalanceService extends AbsService implements BackupProcessor, AccountSetsProcessor, SubjectUseCheckProcessor {

    private final BalanceMapper balanceMapper;
    private final QAuxiliaryDetail qAuxiliaryDetail = QAuxiliaryDetail.auxiliaryDetail;
    private static final QBalance qBalance = QBalance.balance;
    private static final QVoucherDetails qVoucherDetails = QVoucherDetails.voucherDetails;
    private static final QBalanceInitial qBalanceInitial = QBalanceInitial.balanceInitial;
    private static final QSubject qSubject = QSubject.subject;

    private final BalanceRepository balanceRepository;

    private final BalanceInitialRepository balanceInitialRepository;

    private final AuxiliaryDetailRepository auxiliaryDetailRepository;

    private final CurrencyService currencyService;

    /**
     * 获取对应类别科目下的外币列表
     *
     * @param type
     * @param accountSetsId
     * @return
     */
    public List<CurrencyDto> fetchCurrencyWithLocal(String type, Long accountSetsId) {
        List<List<Long>> list = bqf.selectFrom(qSubject)
                .select(qSubject.currencyAccounting)
                .where(qSubject.type.eq(type)
                        .and(qSubject.status.isTrue())
                        .and(qSubject.accountSetsId.eq(accountSetsId)))
                .fetch();
        Set<Long> collect = list.stream().filter(CollUtil::isNotEmpty).flatMap(Collection::stream).collect(Collectors.toSet());
        if (CollUtil.isNotEmpty(collect)) {
            return currencyService.fetchByIdArrWithLocal(accountSetsId, collect);
        }
        return new ArrayList<>();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qBalance)
                .where(qBalance.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<Balance> backupType() {
        return Balance.class;
    }

    /**
     * 恢复备份
     *
     * @param accountSetsId
     * @param json
     * @return
     */
    @Override
    public void recover(Long accountSetsId, String json) {
        if (StrUtil.isNotEmpty(json)) {
            List<Balance> javaList = JSONArray.parseArray(json).toJavaList(backupType());
            javaList.forEach(s -> s.setAccountSetsId(accountSetsId));
            balanceRepository.saveAll(javaList);
        }
    }

    @Override
    @Transactional
    public void clear(Long accountSetsId) {
        jqf.delete(qBalance)
                .where(qBalance.accountSetsId.eq(accountSetsId))
                .execute();
    }


    @Transactional
    public void clearSubjectInit(Long subjectId, Long accountSetsId) {
        jqf.delete(qBalance)
                .where(qBalance.subjectId.eq(subjectId).and(qBalance.accountSetsId.eq(accountSetsId)))
                .execute();
    }

    /**
     * 保存期初辅助项
     *
     * @param accountSets   当前账套
     * @param auxiliaryForm 辅助项
     */
    @Transactional
    public void saveAuxiliary(AccountSets accountSets, BalanceAuxiliaryForm auxiliaryForm) {
        Set<Long> detailSet = new HashSet<>();
        for (List<Detail> details : auxiliaryForm.getDetails()) {
            Long detailId = getDetailId(accountSets, details);

            detailSet.add(detailId);
        }

        //删除不存在的明细
        jqf.delete(qBalanceInitial)
                .where(qBalanceInitial.accountSetsId.eq(accountSets.getId())
                        .and(qBalanceInitial.subjectId.eq(auxiliaryForm.getSubjectId()))
                        .and(qBalanceInitial.currencyId.eq(auxiliaryForm.getCurrencyId()))
                        .and(qBalanceInitial.auxiliaryDetailId.notIn(detailSet).and(qBalanceInitial.auxiliaryDetailId.ne(0L))))
                .execute();

        //查询已存在
        List<Long> existsAuxiliary = bqf.selectFrom(qBalanceInitial)
                .select(qBalanceInitial.auxiliaryDetailId)
                .where(qBalanceInitial.accountSetsId.eq(accountSets.getId())
                        .and(qBalanceInitial.subjectId.eq(auxiliaryForm.getSubjectId()))
                        .and(qBalanceInitial.currencyId.eq(auxiliaryForm.getCurrencyId()))
                        .and(qBalanceInitial.auxiliaryDetailId.in(detailSet)))
                .fetch();
        //删除已存在的
        existsAuxiliary.forEach(detailSet::remove);
        //创建不存在的
        if (CollUtil.isNotEmpty(detailSet)) {
            balanceInitialRepository.saveAll(
                    detailSet.stream().map(detailId ->
                            getBalanceInitial(auxiliaryForm.getSubjectId(), accountSets.getId(), auxiliaryForm.getCurrencyId(), detailId)
                    ).collect(Collectors.toList()));
        }
    }

    @Transactional
    public Long getDetailId(AccountSets accountSets, List<Detail> details) {
        //构建辅助项id组合键
        String calCol = details.stream()
                .filter(s -> s.getValue() != null)
                .sorted(Comparator.comparing(Detail::getCategoryType))
                .map(s -> String.valueOf(s.getValue()))
                .collect(Collectors.joining("_"));
        //查询组合键是否存在
        Long detailId = bqf.selectFrom(qAuxiliaryDetail)
                .select(qAuxiliaryDetail.id)
                .where(qAuxiliaryDetail.calCol.eq(calCol).and(qAuxiliaryDetail.accountSetsId.eq(accountSets.getId())))
                .fetchFirst();
        //不存在创建辅助明细
        if (detailId == null) {
            AuxiliaryDetail ad = new AuxiliaryDetail();
            ad.setDetails(details);
            ad.setCalCol(calCol);
            ad.setId(snowflakeId.generate());
            ad.setAccountSetsId(accountSets.getId());
            auxiliaryDetailRepository.saveAndFlush(ad);
            return ad.getId();
        }
        return detailId;
    }

    /**
     * 期末试算平衡
     *
     * @param accountSetsId
     * @param checkDate
     * @return
     */
    public TrialBalanceDto trialBalance(Long accountSetsId, LocalDate checkDate) {
        TrialBalanceDto.TrialBalanceDtoBuilder builder = TrialBalanceDto.builder();
        CurrencyDto currencyDto = currencyService.fetchLocal(accountSetsId);

        //期初期末余额
        bqf.selectFrom(qBalance)
                .select(qBalance.beginBalance.sum(),
                        qBalance.endBalance.sum(),
                        qSubject.balanceDirection)
                .join(qSubject).on(qSubject.id.eq(qBalance.subjectId).and(qBalance.accountSetsId.eq(qSubject.accountSetsId)))
                .where(qBalance.accountSetsId.eq(accountSetsId)
                        .and(qSubject.level.eq(1))
                        .and(qBalance.auxiliaryDetailId.eq(0L))
                        .and(qBalance.currencyId.eq(currencyDto.getId()))
                        .and(qBalance.date.eq(checkDate)))
                .groupBy(qSubject.balanceDirection)
                .fetch().forEach(tuple -> {
                    if ("借".equals(tuple.get(qSubject.balanceDirection))) {
                        builder.initalDebit(tuple.get(qBalance.beginBalance.sum()));
                        builder.debitTotal(tuple.get(qBalance.endBalance.sum()));
                    } else {
                        builder.initalCredit(tuple.get(qBalance.beginBalance.sum()));
                        builder.creditTotal(tuple.get(qBalance.endBalance.sum()));
                    }
                });
        return builder.build();
    }


    private BalanceInitial getBalanceInitial(Long subjectId, Long accountSetsId, Long currencyId, Long detailId) {
        BalanceInitial balance = new BalanceInitial();
        balance.setCurrencyId(currencyId);
        balance.setAuxiliaryDetailId(detailId);
        balance.setSubjectId(subjectId);
        balance.setAccountSetsId(accountSetsId);
        return balance;
    }

    /**
     * 科目余额
     *
     * @param accountSetsId
     * @param subjectBalanceForm
     * @return
     */
    public BigDecimal subjectBalance(Long accountSetsId, SubjectBalanceForm subjectBalanceForm) {
        CurrencyDto currencyDto = currencyService.fetchLocal(accountSetsId);

        BooleanExpression expression = qBalance.accountSetsId.eq(accountSetsId)
                .and(qBalance.subjectId.eq(subjectBalanceForm.getSubjectId()))
                .and(qBalance.currencyId.eq(Optional.ofNullable(subjectBalanceForm.getCurrencyId()).orElse(currencyDto.getId())));
        if (CollUtil.isNotEmpty(subjectBalanceForm.getAcDetails())) {
            String key = subjectBalanceForm.getAcDetails().stream()
                    .filter(a -> a.getCategoryId() != null)
                    .sorted(Comparator.comparing(AcDetails::getCategoryId))
                    .map(d -> String.valueOf(d.getId())).collect(Collectors.joining("_"));
            Long aaId = null;
            if (StrUtil.isNotEmpty(key)) {
                aaId = bqf.selectFrom(qAuxiliaryDetail)
                        .select(qAuxiliaryDetail.id)
                        .where(qAuxiliaryDetail.accountSetsId.eq(accountSetsId)
                                .and(qAuxiliaryDetail.calCol.eq(key)))
                        .fetchFirst();
            }
            if (aaId != null) {
                expression = expression.and(qBalance.auxiliaryDetailId.eq(aaId));
            } else {
                return BigDecimal.ZERO;
            }
        }

        // TODO 估计还要判断是否是本币
        Balance balance = bqf.selectFrom(qBalance)
                .where(expression)
                .orderBy(qBalance.date.desc())
                .fetchFirst();
        return balance == null ? BigDecimal.ZERO : balance.getEndBalance();
    }

    /**
     * 是否使用了相应科目
     *
     * @param accountSetsId
     * @param subjectId
     * @return
     */
    @Override
    public boolean isSubjectUsed(Long accountSetsId, Long subjectId) {
        List<Balance> balances = bqf.selectFrom(qBalance)
                .where(qBalance.accountSetsId.eq(accountSetsId)
                        .and(qBalance.subjectId.eq(subjectId)))
                .fetch();
        return balances.stream().anyMatch(balance -> balanceMapper.toDto(balance).isUsed());
    }

    /**
     * 首页的应收款项和应付款项
     *
     * @param accountSetsId
     * @param year
     * @param monthValue
     * @return
     */
    public Dict copeTotal(Long accountSetsId, int year, int monthValue) {
        CurrencyDto localCurrency = currencyService.fetchLocal(accountSetsId);
        if (localCurrency == null) {
            log.error("localCurrency 本币为空~");
        }
        Dict dict = Dict.create();
        bqf.selectFrom(qBalance)
                .select(qSubject.code, qBalance.endBalance)
                .join(qSubject).on(qSubject.id.eq(qBalance.subjectId).and(qSubject.accountSetsId.eq(accountSetsId)))
                .where(qBalance.accountSetsId.eq(accountSetsId)
                        .and(qBalance.auxiliaryDetailId.eq(0L))
                        .and(qBalance.year.eq(year))
                        .and(qBalance.period.eq(monthValue))
                        .and(qBalance.currencyId.eq(localCurrency.getId()))
                        .and(qSubject.code.eq("2202").or(qSubject.code.eq("1122")))
                ).fetch().forEach(tuple -> {
                    dict.put(tuple.get(qSubject.code), tuple.get(qBalance.endBalance));
                });
        return dict;
    }

    /**
     * 根据科目id和期间查询科目余额
     *
     * @param accountSetsId
     * @param subjectId
     * @param auxiliaryDetailIdList
     * @param voucherDate
     * @return
     */
    public List<BalanceDto> loadBySubjectIds(Long accountSetsId, Long subjectId, LocalDate voucherDate, Set<Long> auxiliaryDetailIdList, Long currencyId) {
        BooleanExpression expression = qBalance.accountSetsId.eq(accountSetsId)
                .and(qBalance.year.eq(voucherDate.getYear()))
                .and(qBalance.period.eq(voucherDate.getMonthValue()))
                .and(qBalance.currencyId.eq(currencyId))
                .and(qBalance.subjectId.eq(subjectId));

        if (CollUtil.isNotEmpty(auxiliaryDetailIdList)) {
            expression = expression.and(qBalance.auxiliaryDetailId.in(auxiliaryDetailIdList));
        } else {
            expression = expression.and(qBalance.auxiliaryDetailId.eq(0L));
        }

        return bqf.selectFrom(qBalance)
                .select(qBalance, qSubject.balanceDirection)
                .innerJoin(qSubject).on(qBalance.subjectId.eq(qSubject.id).and(qSubject.accountSetsId.eq(qBalance.accountSetsId)))
                .where(expression)
                .fetch().stream().map(tuple -> {
                    BalanceDto dto = balanceMapper.toDto(tuple.get(qBalance));
                    dto.setBalanceDirection(tuple.get(qSubject.balanceDirection));
                    return dto;
                }).collect(Collectors.toList());
    }

    public List<BalanceDto> loadBySubjectIds(Long accountSetsId, Long subjectId, LocalDate start, LocalDate end, Set<Long> auxiliaryDetailIdList, Long currencyId) {
        BooleanExpression expression = qBalance.accountSetsId.eq(accountSetsId)
                .and(qBalance.date.between(start, end))
                .and(qBalance.currencyId.eq(currencyId))
                .and(qBalance.subjectId.eq(subjectId));

        if (CollUtil.isNotEmpty(auxiliaryDetailIdList)) {
            expression = expression.and(qBalance.auxiliaryDetailId.in(auxiliaryDetailIdList));
        } else {
            expression = expression.and(qBalance.auxiliaryDetailId.eq(0L));
        }

        return bqf.selectFrom(qBalance)
                .select(qBalance.subjectId, qBalance.auxiliaryDetailId, qBalance.credit.sum(), qBalance.debit.sum(), qSubject.balanceDirection)
                .innerJoin(qSubject).on(qBalance.subjectId.eq(qSubject.id).and(qSubject.accountSetsId.eq(qBalance.accountSetsId)))
                .where(expression)
                .groupBy(qBalance.subjectId, qBalance.auxiliaryDetailId, qSubject.balanceDirection)
                .fetch().stream().map(tuple -> {
                    BalanceDto dto = new BalanceDto();
                    dto.setSubjectId(tuple.get(qBalance.subjectId));
                    dto.setAuxiliaryDetailId(tuple.get(qBalance.auxiliaryDetailId));
                    dto.setDebit(tuple.get(qBalance.debit.sum()));
                    dto.setCredit(tuple.get(qBalance.credit.sum()));
                    dto.setBalanceDirection(tuple.get(qSubject.balanceDirection));
                    return dto;
                }).collect(Collectors.toList());
    }

    /**
     * 删除辅助期初
     *
     * @param accountSets
     * @param id
     * @param auxiliaryId
     * @return
     */
    @Transactional
    public void delAuxiliary(AccountSets accountSets, Long id, Long auxiliaryId) {
        jqf.delete(qBalanceInitial)
                .where(qBalanceInitial.accountSetsId.eq(accountSets.getId())
                        .and(qBalanceInitial.id.eq(id))
                        .and(qBalanceInitial.auxiliaryDetailId.eq(auxiliaryId)))
                .execute();

        // 判断有没有被使用到，没有被使用到，就删除辅助标识
        long biCount = bqf.selectFrom(qBalanceInitial)
                .where(qBalanceInitial.auxiliaryDetailId.eq(auxiliaryId)
                        .and(qBalanceInitial.accountSetsId.eq(accountSets.getId())))
                .fetchCount();
        long bCount = bqf.selectFrom(qVoucherDetails)
                .where(qVoucherDetails.auxiliaryDetailId.eq(auxiliaryId)
                        .and(qVoucherDetails.accountSetsId.eq(accountSets.getId())))
                .fetchCount();
        if (biCount == 0 && bCount == 0) {
            jqf.delete(qAuxiliaryDetail)
                    .where(qAuxiliaryDetail.id.eq(auxiliaryId)
                            .and(qAuxiliaryDetail.accountSetsId.eq(accountSets.getId())))
                    .execute();
        }
    }

    @Getter
    public static class Query {
        public final BooleanBuilder builder = new BooleanBuilder();
        private AccountSets accountSets;
        private String type;
        private Long currencyId;
        // 综合本币
        private boolean mergerCurrency = true;
        private Long localCurrencyId;
        private boolean local;
        private final Map<String, Object> params = new HashMap<>();

        public void setAccountSets(AccountSets accountSets) {
            this.accountSets = accountSets;
            params.put("accountSetsId", accountSets.getId());
            builder.and(qBalance.accountSetsId.eq(accountSets.getId()));
        }

        public void setType(String type) {
            this.type = type;
            if (StrUtil.isNotEmpty(type)) {
                params.put("type", type);
                builder.and(qSubject.type.eq(type));
            }
        }

        public void setCurrencyId(Long currencyId) {
            this.currencyId = currencyId;
            if (currencyId != null && currencyId != 0L) {
                params.put("currencyId", String.valueOf(currencyId));
                builder.and(qBalance.currencyId.eq(currencyId));
                this.mergerCurrency = false;
            }
        }

        public void setLocalCurrencyId(Long localCurrencyId) {
            this.localCurrencyId = localCurrencyId;
            this.local = Objects.equals(localCurrencyId, currencyId);
            params.put("isLocal", this.local);
        }
    }
}
