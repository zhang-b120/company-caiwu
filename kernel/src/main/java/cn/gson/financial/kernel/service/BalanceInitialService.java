package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.annotation.CopyField;
import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.model.dto.*;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.enums.SubjectCategory;
import cn.gson.financial.kernel.model.mapper.BalanceInitialMapper;
import cn.gson.financial.kernel.model.mapper.SubjectMapper;
import cn.gson.financial.kernel.model.repo.BalanceInitialRepository;
import cn.gson.financial.kernel.model.vo.SubjectVo;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.alibaba.fastjson.JSON;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 *
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年06月07日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BalanceInitialService extends AbsService implements BackupProcessor, AccountSetsProcessor {
    private final BalanceInitialMapper balanceInitialMapper;

    private final CurrencyService currencyService;

    private final SubjectMapper subjectMapper;

    private final BalanceInitialRepository balanceInitialRepository;

    private final QBalanceInitial qBalanceInitial = QBalanceInitial.balanceInitial;

    private final QSubject qSubject = QSubject.subject;

    private final QAuxiliaryDetail qAuxiliaryDetail = QAuxiliaryDetail.auxiliaryDetail;

    @Transactional
    public void updateInitBalance(List<BalanceInitialDto> balanceList, AccountSets accountSets) {
        List<BalanceInitial> balances = toBalanceInitial(accountSets, balanceList);
        jqf.delete(qBalanceInitial)
                .where(qBalanceInitial.accountSetsId.eq(accountSets.getId()))
                .execute();
        if (CollUtil.isNotEmpty(balances)) {
            balanceInitialRepository.saveAll(balances);
        }
    }

    @Transactional
    public void saveInitBalance(List<BalanceInitialDto> balanceList, AccountSets accountSets) {
        List<BalanceInitial> balances = toSaveBalanceInitial(accountSets, balanceList);
        List<BalanceInitial> balances2 = balances.stream().filter(BalanceInitial::isNotEmpty).toList();
        if (CollUtil.isNotEmpty(balances2)) {
            balanceInitialRepository.saveAll(balances2);
        }
        List<Long> emptyList = balances.stream().filter(b -> !b.isNotEmpty()).map(BalanceInitial::getId).toList();
        if (CollUtil.isNotEmpty(emptyList)) {
            jqf.delete(qBalanceInitial)
                    .where(qBalanceInitial.accountSetsId.eq(accountSets.getId())
                            .and(qBalanceInitial.currencyId.eq(balances.get(0).getCurrencyId()))
                            .and(qBalanceInitial.id.in(emptyList)))
                    .execute();
        }
    }

    public List<BalanceDto> list(Long accountSetsId, BalanceService.Query query) {
        //所有科目
        List<BalanceDto> balanceList = lazyDao.findBySql("subjectList", query.getParams(), SubjectDto.class)
                .stream().map(subjectMapper::toBalanceDto).toList();

        // 非本币时，递归查询父级科目
        if (!query.isLocal() && !query.isMergerCurrency()) {
            List<BalanceDto> allList = lazyDao.findBySql("parentSubjectList", query.getParams(), SubjectDto.class)
                    .stream().map(subjectMapper::toBalanceDto).toList();
            Map<Long, BalanceDto> subjectMap = allList.stream().collect(Collectors.toMap(BalanceDto::getSubjectId, d -> d));
            List<BalanceDto> parents = new ArrayList<>();
            for (BalanceDto balanceDto : balanceList) {
                recursionParent(balanceDto, subjectMap, parents);
            }
            parents.addAll(balanceList);
            balanceList = parents;
        }

        //将科目通过id和币别组合归类
        Map<String, BalanceDto> bcMap = balanceList.stream().collect(Collectors.toMap(d -> StrUtil.join("/", d.getSubjectId(), query.getCurrencyId()), d -> {
            d.setCurrencyId(query.getCurrencyId());
            return d;
        }));

        //构建科目的临时数据
        Map<Long, BalanceDto> bMap = balanceList.stream().collect(Collectors.toMap(BalanceDto::getSubjectId, d -> d));
        //从余额表中取出已有科目余额数据
        List<Tuple> tuples;
        BooleanExpression expression = qBalanceInitial.accountSetsId.eq(accountSetsId)
                .and(qBalanceInitial.subjectId.in(balanceList.stream().map(BalanceDto::getSubjectId).collect(Collectors.toList())));
        if (!query.isMergerCurrency()) {
            expression = expression.and(qBalanceInitial.currencyId.eq(query.getCurrencyId()));
        }

        //从余额表中取出已有科目余额数据
        tuples = bqf.selectFrom(qBalanceInitial)
                .select(qBalanceInitial, qAuxiliaryDetail.details)
                .leftJoin(qAuxiliaryDetail).on(qAuxiliaryDetail.id.eq(qBalanceInitial.auxiliaryDetailId)
                        .and(qAuxiliaryDetail.accountSetsId.eq(accountSetsId)))
                .where(expression).fetch();


        //所有关联辅助项目
        Map<AccountingCategoryType, Set<Long>> categoryTypeListMap = tuples.stream().filter(tuple -> CollUtil.isNotEmpty(tuple.get(qAuxiliaryDetail.details)))
                .flatMap(tuple -> tuple.get(qAuxiliaryDetail.details).stream())
                .collect(Collectors.groupingBy(AuxiliaryDetail.Detail::getCategoryType, Collectors.mapping(AuxiliaryDetail.Detail::getValue, Collectors.toSet())));

        //取出辅助项数据
        Map<Long, AcDetails> acDetailsMap = new HashMap<>();
        if (!categoryTypeListMap.isEmpty()) {
            categoryTypeListMap.forEach((type, details) -> {
                AuxiliaryDataService dataService = SpringUtil.getBean(type.dataService);
                acDetailsMap.putAll(dataService.loadDataByIdList(accountSetsId, details));
            });
        }

        //科目数据和余额数据进行合并后的期初数据
        List<BalanceDto> mergeBalanceList = new ArrayList<>();

        for (Tuple tuple : tuples) {
            BalanceDto dto = BeanUtil.toBean(tuple.get(qBalanceInitial), BalanceDto.class);
            BeanUtil.copyProperties(bMap.get(dto.getSubjectId()), dto, CopyOptions.create().setPropertiesFilter((s, a) -> s.isAnnotationPresent(CopyField.class)));
            dto.setCurrencyId(query.getCurrencyId());
            dto.setYearTotalDebit(NumberUtil.sub(dto.getYearTotalDebit(), dto.getDebit()));
            dto.setYearTotalDebitFor(NumberUtil.sub(dto.getYearTotalDebitFor(), dto.getDebitFor()));
            dto.setYearTotalCredit(NumberUtil.sub(dto.getYearTotalCredit(), dto.getCredit()));
            dto.setYearTotalCreditFor(NumberUtil.sub(dto.getYearTotalCreditFor(), dto.getCreditFor()));
            dto.setYearTotalDebitQuantity(NumberUtil.sub(dto.getYearTotalDebitQuantity(), dto.getDebitQuantity()));
            dto.setYearTotalCreditQuantity(NumberUtil.sub(dto.getYearTotalCreditQuantity(), dto.getCreditQuantity()));

            //辅助项的科目
            List<AuxiliaryDetail.Detail> details = tuple.get(qAuxiliaryDetail.details);
            if (CollUtil.isNotEmpty(details) && CollUtil.isNotEmpty(acDetailsMap)) {
                dto.setDetails(details.stream().filter(d -> acDetailsMap.get(d.getValue()) != null).toList());
                dto.setLeaf(true);
                dto.setTreeId(dto.getAuxiliaryDetailId() + dto.getSubjectId());
                dto.setParentTreeId(dto.getSubjectId());
                String auxiliaryCode = dto.getDetails().stream().map(d -> acDetailsMap.get(d.getValue()).getCode()).collect(Collectors.joining("_"));
                String auxiliaryName = dto.getDetails().stream().map(d -> acDetailsMap.get(d.getValue()).getName()).collect(Collectors.joining("_"));
                dto.setAuxiliaryCode(StrUtil.emptyToDefault(auxiliaryCode, "【辅助已删除】"));
                dto.setAuxiliaryName(StrUtil.emptyToDefault(auxiliaryName, "【辅助已删除,请移除本项】"));
                dto.setSubjectCode(dto.getSubjectCode() + "_" + dto.getAuxiliaryCode());
                dto.setSubjectName(dto.getSubjectName() + "_" + dto.getAuxiliaryName());
                if (bMap.get(dto.getParentTreeId()) != null) {
                    bMap.get(dto.getParentTreeId()).setLeaf(false);
                }
            } else {
                bMap.compute(dto.getTreeId(), (k, d) -> {
                    if (d == null) {
                        return dto;
                    }
                    dto.setBeginBalance(NumberUtil.add(d.getBeginBalance(), dto.getBeginBalance()));
                    dto.setBeginQuantity(NumberUtil.add(d.getBeginQuantity(), dto.getBeginQuantity()));
                    dto.setCredit(NumberUtil.add(d.getCredit(), dto.getCredit()));
                    dto.setCreditQuantity(NumberUtil.add(d.getCreditQuantity(), dto.getCreditQuantity()));
                    dto.setDebit(NumberUtil.add(d.getDebit(), dto.getDebit()));
                    dto.setDebitQuantity(NumberUtil.add(d.getDebitQuantity(), dto.getDebitQuantity()));
                    dto.setYearTotal(NumberUtil.add(d.getYearTotal(), dto.getYearTotal()));
                    dto.setYearTotalQuantity(NumberUtil.add(d.getYearTotalQuantity(), dto.getYearTotalQuantity()));
                    return dto;
                });
            }

            mergeBalanceList.add(dto);
        }
        for (BalanceDto mbl : mergeBalanceList) {
            String key = StrUtil.join("/", mbl.getSubjectId(), query.getCurrencyId());
            if (bcMap.containsKey(key) && mbl.getAuxiliaryDetailId() == 0L) {
                bcMap.put(key, mbl);
            } else {
                bcMap.put(key + mbl.getAuxiliaryDetailId(), mbl);
            }
        }
        balanceList = new ArrayList<>(bcMap.values());
        balanceList.forEach(b -> {
            if (b.getId() == null) {
                b.setId(snowflakeId.generate());
            }
        });
        bcMap = null;
        bMap = null;
        return balanceList.stream()
                .sorted(Comparator.comparing(BalanceDto::getSubjectCode))
                .collect(Collectors.toList());
    }

    /**
     * 递归找出父节点科目
     *
     * @param balanceDto
     * @param subjectMap
     * @param parents
     */
    private void recursionParent(BalanceDto balanceDto, Map<Long, BalanceDto> subjectMap, List<BalanceDto> parents) {
        BalanceDto balanceDto1 = subjectMap.get(balanceDto.getParentTreeId());
        if (balanceDto1 != null) {
            parents.add(balanceDto1);
            recursionParent(balanceDto1, subjectMap, parents);
        }
    }


    /**
     * 试算平衡
     *
     * @param accountSetsId
     * @return
     */
    public TrialBalanceDto trialBalance(Long accountSetsId) {
        TrialBalanceDto.TrialBalanceDtoBuilder builder = TrialBalanceDto.builder();

        //期初余额
        bqf.selectFrom(qBalanceInitial)
                .select(qBalanceInitial.beginBalance.sum(),
                        qSubject.balanceDirection)
                .join(qSubject).on(qSubject.id.eq(qBalanceInitial.subjectId).and(qBalanceInitial.accountSetsId.eq(qSubject.accountSetsId)))
                .where(qBalanceInitial.accountSetsId.eq(accountSetsId)
                        .and(qBalanceInitial.auxiliaryDetailId.eq(0L))
                        .and(qSubject.level.eq(1)))
                .groupBy(qSubject.balanceDirection)
                .fetch().forEach(tuple -> {
                    if ("借".equals(tuple.get(qSubject.balanceDirection))) {
                        builder.initalDebit(tuple.get(qBalanceInitial.beginBalance.sum()));
                    } else {
                        builder.initalCredit(tuple.get(qBalanceInitial.beginBalance.sum()));
                    }
                });

        //累计发生
        bqf.selectFrom(qBalanceInitial)
                .select(qBalanceInitial.yearTotalDebit.sum(),
                        qBalanceInitial.yearTotalCredit.sum())
                .join(qSubject).on(qSubject.id.eq(qBalanceInitial.subjectId).and(qBalanceInitial.accountSetsId.eq(qSubject.accountSetsId)))
                .where(qBalanceInitial.accountSetsId.eq(accountSetsId)
                        .and(qBalanceInitial.auxiliaryDetailId.eq(0L))
                        .and(qSubject.level.eq(1)))
                .groupBy(qSubject.accountSetsId)
                .fetch().forEach(tuple -> {
                    builder.debitTotal(tuple.get(qBalanceInitial.yearTotalDebit.sum()));
                    builder.creditTotal(tuple.get(qBalanceInitial.yearTotalCredit.sum()));
                });

        List<Tuple> tuples = bqf.selectFrom(qBalanceInitial)
                .select(qBalanceInitial.yearTotal.sum(), qSubject.balanceDirection)
                .join(qSubject).on(qSubject.id.eq(qBalanceInitial.subjectId).and(qBalanceInitial.accountSetsId.eq(qSubject.accountSetsId)))
                .where(qBalanceInitial.accountSetsId.eq(accountSetsId)
                        .and(qBalanceInitial.auxiliaryDetailId.eq(0L))
                        .and(qSubject.level.eq(1))
                        .and(qSubject.type.in(CollUtil.newArrayList(SubjectCategory.ASSET.display, SubjectCategory.COST.display))))
                .groupBy(qBalanceInitial.accountSetsId, qSubject.balanceDirection)
                .fetch();

        AtomicReference<BigDecimal> debit = new AtomicReference<>(BigDecimal.ZERO);
        tuples.stream().filter(tuple -> Objects.equals(tuple.get(qSubject.balanceDirection), "借")).findFirst().ifPresent(tuple -> {
            debit.set(tuple.get(qBalanceInitial.yearTotal.sum()));
        });

        AtomicReference<BigDecimal> credit = new AtomicReference<>(BigDecimal.ZERO);
        tuples.stream().filter(tuple -> Objects.equals(tuple.get(qSubject.balanceDirection), "贷")).findFirst().ifPresent(tuple -> {
            credit.set(tuple.get(qBalanceInitial.yearTotal.sum()));
        });

        //资产合计
        builder.totalDebit(NumberUtil.sub(debit.get(), credit.get()));

        List<Tuple> tupleList = bqf.selectFrom(qBalanceInitial)
                .select(qBalanceInitial.yearTotal.sum(), qSubject.balanceDirection)
                .join(qSubject).on(qSubject.id.eq(qBalanceInitial.subjectId).and(qBalanceInitial.accountSetsId.eq(qSubject.accountSetsId)))
                .where(qBalanceInitial.accountSetsId.eq(accountSetsId)
                        .and(qBalanceInitial.auxiliaryDetailId.eq(0L))
                        .and(qSubject.level.eq(1))
                        .and(qSubject.type.in(CollUtil.newArrayList(SubjectCategory.LIABILITY.display, SubjectCategory.RIGHTS.display))))
                .groupBy(qBalanceInitial.accountSetsId, qSubject.balanceDirection)
                .fetch();

        AtomicReference<BigDecimal> debit2 = new AtomicReference<>(BigDecimal.ZERO);
        tupleList.stream().filter(tuple -> Objects.equals(tuple.get(qSubject.balanceDirection), "借")).findFirst().ifPresent(tuple -> {
            debit2.set(tuple.get(qBalanceInitial.yearTotal.sum()));
        });

        AtomicReference<BigDecimal> credit2 = new AtomicReference<>(BigDecimal.ZERO);
        tupleList.stream().filter(tuple -> Objects.equals(tuple.get(qSubject.balanceDirection), "贷")).findFirst().ifPresent(tuple -> {
            credit2.set(tuple.get(qBalanceInitial.yearTotal.sum()));
        });

        //负债和权益合计
        builder.totalCredit(NumberUtil.sub(credit2.get(), debit2.get()));
        return builder.build();
    }

    public void updateInitBalance(List<BalanceInitialDto> balanceList, AccountSets accountSets, Map<String, SubjectVo> subjectVos) {
        //本币
        CurrencyDto localCurrency = currencyService.fetchLocal(accountSets.getId());

        Map<Long, SubjectVo> subjectVoMap = subjectVos.values().stream().collect(Collectors.toMap(SubjectVo::getId, s -> s));

        balanceList.forEach(b -> {
            SubjectVo subjectVo = subjectVoMap.get(b.getSubjectId());
            b.setLevel(subjectVo.getLevel());
            b.setLeaf(subjectVo.getLeaf());
            b.setParentTreeId(subjectVo.getParentId());
            b.setTreeId(subjectVo.getId());
            b.setBalanceDirection(subjectVo.getBalanceDirection());
            if (b.getCurrencyId() == null) {
                b.setCurrencyId(localCurrency.getId());
            }
            if (!b.getLeaf()) {
                b.clear();
            }
        });

        List<BalanceInitial> balances = new ArrayList<>();

        Map<Long, BalanceInitialDto> subjectVoMap2 = balanceList.stream()
                .filter(b -> b.getAuxiliaryDetailId() == null || b.getAuxiliaryDetailId() == 0L)
                .collect(Collectors.toMap(BalanceInitialDto::getSubjectId, s -> s));

        Map<Long, List<BalanceInitialDto>> auxiliaryDetailMap = balanceList.stream()
                .filter(b -> b.getAuxiliaryDetailId() != null && b.getAuxiliaryDetailId() != 0L)
                .collect(Collectors.groupingBy(BalanceInitialDto::getSubjectId, Collectors.toList()));

        for (Map.Entry<Long, List<BalanceInitialDto>> entry : auxiliaryDetailMap.entrySet()) {
            Long subjectId = entry.getKey();
            List<BalanceInitialDto> list = entry.getValue();
            BalanceInitialDto balanceInitialDto = subjectVoMap2.get(subjectId);
            balanceInitialDto.clear();
            list.forEach(balanceInitialDto::add);
            balances.addAll(list.stream().map(s -> {
                BalanceInitial entity = balanceInitialMapper.toEntity(s);
                entity.setId(snowflakeId.generate());
                entity.setAccountSetsId(accountSets.getId());
                return entity;
            }).toList());
        }

        balanceList = CollUtil.newArrayList(subjectVoMap2.values());

        List<BalanceInitial> list = balanceList.stream().sorted(Comparator.comparing(BalanceInitialDto::getLevel).reversed()).map(dto -> {
            if (dto.getLevel() != 1) {
                subjectVoMap2.get(dto.getParentTreeId()).add(dto);
                subjectVoMap2.get(dto.getParentTreeId()).calculateYearTotal();
            }

            BalanceInitial entity = balanceInitialMapper.toEntity(dto);
            entity.setId(snowflakeId.generate());
            entity.setAccountSetsId(accountSets.getId());
            return entity;
        }).toList();

        balances.addAll(list);
        balances = balances.stream().filter(BalanceInitial::isNotEmpty).toList();
        if (CollUtil.isNotEmpty(balances)) {
            balanceInitialRepository.saveAll(balances);
        }
    }

    /**
     * 导入期初转换
     * @param accountSets
     * @param balanceInitialDtos
     * @return
     */
    private List<BalanceInitial> toBalanceInitial(AccountSets accountSets, List<BalanceInitialDto> balanceInitialDtos) {
        List<BalanceInitial> balances = new ArrayList<>();
        for (BalanceInitialDto b : balanceInitialDtos) {
            BalanceInitial balance = new BalanceInitial();
            balance.setId(snowflakeId.generate());
            balance.setAccountSetsId(accountSets.getId());
            balances.add(balanceInitialMapper.partialUpdate(b, balance));
        }
        return balances;
    }

    /**
     * 界面保存期初转换为BalanceInitial
     * @param accountSets
     * @param balanceInitialDtos
     * @return
     */
    private List<BalanceInitial> toSaveBalanceInitial(AccountSets accountSets, List<BalanceInitialDto> balanceInitialDtos) {
        List<BalanceInitial> balances = new ArrayList<>();
        for (BalanceInitialDto b : balanceInitialDtos) {
            BalanceInitial balanceInitial = bqf.selectFrom(qBalanceInitial)
                    .where(qBalanceInitial.subjectId.eq(b.getSubjectId())
                            .and(qBalanceInitial.accountSetsId.eq(b.getAccountSetsId()))
                            .and(qBalanceInitial.auxiliaryDetailId.eq(b.getAuxiliaryDetailId()))
                            .and(qBalanceInitial.currencyId.eq(b.getCurrencyId())))
                    .fetchFirst();
            if (balanceInitial != null) {
                balances.add(balanceInitialMapper.partialUpdate(b, balanceInitial));
            } else {
                BalanceInitial balance = new BalanceInitial();
                balance.setId(snowflakeId.generate());
                balance.setAccountSetsId(accountSets.getId());
                balances.add(balanceInitialMapper.partialUpdate(b, balance));
            }
        }
        return balances;
    }

    /**
     * 按账套统计期初条数，用于首页展示是否完成期初录入。
     *
     * @param accountSetsIds 账套ID列表
     * @return 账套ID -> 期初条数（仅包含条数大于0的账套）
     */
    public Map<Long, Long> countByAccountSetsIds(java.util.List<Long> accountSetsIds) {
        if (CollUtil.isEmpty(accountSetsIds)) {
            return new HashMap<>();
        }
        List<Tuple> tuples = bqf.selectFrom(qBalanceInitial)
                .select(qBalanceInitial.accountSetsId, qBalanceInitial.count())
                .where(qBalanceInitial.accountSetsId.in(accountSetsIds))
                .groupBy(qBalanceInitial.accountSetsId)
                .fetch();
        return tuples.stream()
                .collect(Collectors.toMap(t -> t.get(qBalanceInitial.accountSetsId), t -> t.get(qBalanceInitial.count())));
    }

    /**
     * 备份
     *
     * @param accountSetsId
     * @return
     */
    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qBalanceInitial)
                .where(qBalanceInitial.accountSetsId.eq(accountSetsId))
                .fetch());
    }

    /**
     * 备份类型
     *
     * @return
     */
    @Override
    public Class<?> backupType() {
        return BalanceInitial.class;
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
        List<BalanceInitial> balanceInitials = JSON.parseArray(json, BalanceInitial.class);
        balanceInitialRepository.saveAll(balanceInitials.stream().peek(s -> s.setAccountSetsId(accountSetsId))
                .toList());
    }

    /**
     * 账套数据清理
     *
     * @param accountSetsId
     */
    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qBalanceInitial)
                .where(qBalanceInitial.accountSetsId.eq(accountSetsId))
                .execute();
    }

    /**
     * 检查期初辅助信息是否都在系统已定义
     * @param auxiliaryDetailIds
     * @param accountSetsId
     * @return
     */
    public boolean checkAuxiliaryDetailIds(Set<Long> auxiliaryDetailIds, Long accountSetsId) {
        return bqf.selectFrom(qAuxiliaryDetail)
                       .where(qAuxiliaryDetail.id.in(auxiliaryDetailIds).and(qAuxiliaryDetail.accountSetsId.eq(accountSetsId)))
                       .fetchCount() == auxiliaryDetailIds.size();
    }
}
