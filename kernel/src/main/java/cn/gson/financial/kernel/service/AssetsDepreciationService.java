package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.config.VoucherProcessor;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.DepreciationDto;
import cn.gson.financial.kernel.model.dto.VoucherDetailsDto;
import cn.gson.financial.kernel.model.dto.VoucherDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AssetsChangeType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.AssetsDepreciationRepository;
import cn.gson.financial.kernel.model.repo.AssetsDetailRepository;
import cn.gson.financial.kernel.model.vo.SubjectVo;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.NumberUtil;
import com.alibaba.fastjson.JSON;
import com.blazebit.persistence.PagedList;
import com.blazebit.persistence.querydsl.BlazeJPAQuery;
import com.querydsl.core.Tuple;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 *
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (Depreciation)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022-11-25 09:41:40</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AssetsDepreciationService extends AbsService implements AccountSetsProcessor, VoucherProcessor, BackupProcessor {

    private static final QAssetsDepreciation qAssetsDepreciation = QAssetsDepreciation.assetsDepreciation;

    private final QVoucher qVoucher = QVoucher.voucher;

    private static final QAssets qAssets = QAssets.assets;

    private final AssetsDetailRepository assetsDetailRepository;

    private final QAssetsDetail qAssetsDetail = QAssetsDetail.assetsDetail;

    private final QAssetsChange qAssetsChange = QAssetsChange.assetsChange;

    private final AssetsDepreciationRepository depreciationRepository;

    private final SubjectService subjectService;

    private final QAccountSets qAccountSets = QAccountSets.accountSets;

    // @Lazy 打破循环依赖：VoucherService → VoucherProcessor → AssetsDepreciationService
    @Resource
    @Lazy
    private VoucherService voucherService;

    @Resource
    @Lazy
    private CurrencyService currencyService;

    @Transactional
    public AssetsDepreciation save(AssetsDepreciation depreciation) {
        if (depreciation.getId() != null) {
            AssetsDepreciation original = depreciationRepository.getReferenceById(depreciation.getPrimaryKey());
            BeanUtil.copyProperties(depreciation, original, CopyOptions.create().ignoreNullValue());
            depreciation = depreciationRepository.save(original);
        } else {
            depreciation = depreciationRepository.save(depreciation);
        }
        return depreciation;
    }

    /**
     * 计提折旧
     *
     * @param primaryKey
     */
    @Transactional
    public void depreciation(PrimaryKey primaryKey) {
        AssetsDepreciation depreciation = depreciationRepository.getReferenceById(primaryKey);

        // 基于开始使用日期判断是否需要计提折旧
        // 如果开始使用日期为空，则回退到使用录入期间（兼容旧数据）
        // 使用年月比较，确保比较的是月份而不是具体日期
        LocalDate checkDateMonth = depreciation.getCheckDate().withDayOfMonth(1);
        long count = bqf.selectFrom(qAssets)
                .where(
                    // 情况1：有开始使用日期
                    // 开始使用日期的年月 < 计提期间的年月，或者（开始使用日期的年月 = 计提期间的年月 且 录入当期是否折旧 = true）
                    (qAssets.startDate.isNotNull().and(
                        (qAssets.startDate.year().lt(checkDateMonth.getYear())
                        .or(qAssets.startDate.year().eq(checkDateMonth.getYear())
                            .and(qAssets.startDate.month().lt(checkDateMonth.getMonthValue()))))
                        .or(qAssets.startDate.year().eq(checkDateMonth.getYear())
                            .and(qAssets.startDate.month().eq(checkDateMonth.getMonthValue()))
                            .and(qAssets.isPeriod.isTrue()))
                    ))
                    // 情况2：开始使用日期为空（兼容旧数据）
                    // 录入期间的年月 < 计提期间的年月，或者（录入期间的年月 = 计提期间的年月 且 录入当期是否折旧 = true）
                    .or(qAssets.startDate.isNull().and(
                        (qAssets.entryDate.year().lt(checkDateMonth.getYear())
                        .or(qAssets.entryDate.year().eq(checkDateMonth.getYear())
                            .and(qAssets.entryDate.month().lt(checkDateMonth.getMonthValue()))))
                        .or(qAssets.entryDate.year().eq(checkDateMonth.getYear())
                            .and(qAssets.entryDate.month().eq(checkDateMonth.getMonthValue()))
                            .and(qAssets.isPeriod.isTrue()))
                    ))
                    // 账套ID条件
                    .and(qAssets.accountSetsId.eq(primaryKey.getAccountSetsId()))
                )
                .fetchCount();

        depreciation.setStatus(count > 0 ? 2 : 4);
        depreciationRepository.save(depreciation);


        //创建下一个期间
        LocalDate nextCheckDate = depreciation.getCheckDate().plusMonths(1);
        AssetsDepreciation nextDepreciation = bqf.selectFrom(qAssetsDepreciation)
                .where(qAssetsDepreciation.checkDate.eq(nextCheckDate).and(qAssetsDepreciation.accountSetsId.eq(primaryKey.getAccountSetsId())))
                .fetchFirst();
        if (nextDepreciation == null) {
            nextDepreciation = new AssetsDepreciation();
            nextDepreciation.setAccountSetsId(primaryKey.getAccountSetsId());
            nextDepreciation.setCheckYear(nextCheckDate.getYear());
            nextDepreciation.setCheckMonth(nextCheckDate.getMonthValue());
            nextDepreciation.setStatus(1);
            nextDepreciation.setCheckDate(nextCheckDate);
            depreciationRepository.save(nextDepreciation);
        }

        BlazeJPAQuery<BigDecimal> originalValueFiled = bqf.selectFrom(qAssetsChange)
                .select(qAssetsChange.afterChange.castToNum(BigDecimal.class))
                .where(qAssetsChange.accountSetsId.eq(primaryKey.getAccountSetsId())
                        .and(qAssetsChange.assetsId.eq(qAssets.id))
                        .and(qAssetsChange.changeType.in(AssetsChangeType.新增, AssetsChangeType.原值调整))
                        .and(qAssetsChange.checkDate.loe(depreciation.getCheckDate())))
                .orderBy(qAssetsChange.checkDate.desc(), qAssetsChange.id.desc()).limit(1);

        // 基于开始使用日期查询需要计提折旧的资产
        // 如果开始使用日期为空，则回退到使用录入期间（兼容旧数据）
        // 使用年月比较，确保比较的是月份而不是具体日期
        List<Tuple> assetsList = bqf.selectFrom(qAssets)
                .select(qAssets, originalValueFiled)
                .where(qAssets.status.eq(1)
                        .and(
                            // 开始使用日期的年月 <= 计提期间的年月，或者（开始使用日期为空 且 录入期间的年月 <= 计提期间的年月）
                            (qAssets.startDate.isNotNull().and(
                                qAssets.startDate.year().lt(checkDateMonth.getYear())
                                .or(qAssets.startDate.year().eq(checkDateMonth.getYear())
                                    .and(qAssets.startDate.month().loe(checkDateMonth.getMonthValue())))
                            ))
                            .or(qAssets.startDate.isNull().and(
                                qAssets.entryDate.year().lt(checkDateMonth.getYear())
                                .or(qAssets.entryDate.year().eq(checkDateMonth.getYear())
                                    .and(qAssets.entryDate.month().loe(checkDateMonth.getMonthValue())))
                            ))
                        )
                        .and(qAssets.expectUseMonth.subtract(qAssets.useMonth).gt(0))
                        .and(qAssets.disposeType.isNull())
                        .and(qAssets.accountSetsId.eq(primaryKey.getAccountSetsId())))
                .fetch();

        //资产折旧记录
        for (Tuple tuple : assetsList) {
            Assets assets = tuple.get(qAssets);
            // 判断是否跳过当期计提：如果录入当期不折旧，且（开始使用日期的年月=计提期间的年月 或 开始使用日期为空且录入期间的年月=计提期间的年月），则跳过
            boolean shouldSkip = false;
            if (!assets.getIsPeriod()) {
                if (assets.getStartDate() != null) {
                    // 有开始使用日期，判断开始使用日期的年月是否等于计提期间的年月
                    shouldSkip = assets.getStartDate().getYear() == checkDateMonth.getYear()
                            && assets.getStartDate().getMonthValue() == checkDateMonth.getMonthValue();
                } else {
                    // 开始使用日期为空，回退到使用录入期间判断
                    shouldSkip = assets.getEntryDate().getYear() == checkDateMonth.getYear()
                            && assets.getEntryDate().getMonthValue() == checkDateMonth.getMonthValue();
                }
            }
            if (shouldSkip) {
                continue;
            }
            BigDecimal originalValue = tuple.get(originalValueFiled);
            BigDecimal depreciationBalance = Optional.ofNullable(bqf.selectFrom(qAssetsDetail)
                    .select(qAssetsDetail.depreciationBalance)
                    .where(qAssetsDetail.assertId.eq(assets.getId()).and(qAssetsDetail.accountSetsId.eq(primaryKey.getAccountSetsId())))
                    .orderBy(qAssetsDetail.id.desc())
                    .fetchFirst()).orElse(BigDecimal.ZERO);
            //资产记录
            AssetsDetail assetsDetail = new AssetsDetail();
            assetsDetail.setAssertId(assets.getId());
            assetsDetail.setDepreciationId(depreciation.getId());
            assetsDetail.setAccountSetsId(primaryKey.getAccountSetsId());
            assetsDetail.setCheckDate(depreciation.getCheckDate().withDayOfMonth(depreciation.getCheckDate().getDayOfMonth()));
            assetsDetail.setDigest("计提折旧");
            assetsDetail.setOriginalDebit(BigDecimal.ZERO);
            assetsDetail.setOriginalCredit(BigDecimal.ZERO);
            assetsDetail.setOriginalBalance(assets.getOriginalValue());
            assetsDetail.setDepreciationDebit(BigDecimal.ZERO);
            assetsDetail.setDepreciationCredit(assets.currentMonthDepreciation(originalValue));
            assetsDetail.setDepreciationBalance(depreciationBalance.add(assets.getCurrentMonthDepreciation()));
            assetsDetail.setImpairmentDebit(BigDecimal.ZERO);
            assetsDetail.setImpairmentCredit(BigDecimal.ZERO);
            assetsDetail.setImpairmentBalance(BigDecimal.ZERO);
            assetsDetail.setNetValue(NumberUtil.sub(assets.getOriginalValue(), assetsDetail.getDepreciationBalance()));
            assetsDetail.setDeptId(assets.getDeptId());
            assetsDetail.setUseState("正常");

            assetsDetailRepository.save(assetsDetail);
        }
    }

    /**
     * 取消计提折旧
     *
     * @param primaryKey
     */
    @Transactional
    public void cancelDepreciation(PrimaryKey primaryKey) {
        AssetsDepreciation depreciation = depreciationRepository.getReferenceById(primaryKey);
        Assert.isNull(depreciation.getVoucherId(), "已生成凭证，不能取消计提折旧");

        //之后的折旧记录
        List<AssetsDepreciation> afterDepreciationList = bqf.selectFrom(qAssetsDepreciation)
                .where(qAssetsDepreciation.checkDate.gt(depreciation.getCheckDate()).and(qAssetsDepreciation.accountSetsId.eq(primaryKey.getAccountSetsId())))
                .fetch();

        Assert.isFalse(afterDepreciationList.stream().anyMatch(s -> s.getVoucherId() != null), "已有期间生成凭证，不能取消当前计提折旧");

        depreciation.setStatus(1);
        depreciation.setCancel(true);
        depreciationRepository.save(depreciation);
        depreciationRepository.deleteAll(afterDepreciationList);
        //删除计提记录
        List<Long> collect = afterDepreciationList.stream().map(AssetsDepreciation::getId).collect(Collectors.toList());
        collect.add(primaryKey.getId());
        jqf.delete(qAssetsDetail)
                .where(qAssetsDetail.depreciationId.in(collect)
                        .and(qAssetsDetail.accountSetsId.eq(primaryKey.getAccountSetsId())))
                .execute();

    }

    @Transactional
    public PageResults<DepreciationDto> list(Long accountSetsId, Page page) {
        // 期间所有新增记录 - 基于资产的开始使用日期（或录入期间）判断
        // 如果开始使用日期不为空，使用开始使用日期的年月；否则使用录入期间的年月
        BlazeJPAQuery<Long> newTotalNumCol = bqf.selectFrom(qAssetsChange)
                .join(qAssets).on(qAssets.id.eq(qAssetsChange.assetsId).and(qAssetsChange.accountSetsId.eq(qAssets.accountSetsId)))
                .select(qAssetsChange.count())
                .where(qAssetsChange.accountSetsId.eq(accountSetsId)
                        .and(qAssetsChange.changeType.eq(AssetsChangeType.新增))
                        .and(
                            // 有开始使用日期：开始使用日期的年月 = 计提期间的年月
                            (qAssets.startDate.isNotNull().and(
                                qAssets.startDate.year().eq(qAssetsDepreciation.checkDate.year())
                                .and(qAssets.startDate.month().eq(qAssetsDepreciation.checkDate.month()))
                            ))
                            // 没有开始使用日期：录入期间的年月 = 计提期间的年月
                            .or(qAssets.startDate.isNull().and(
                                qAssets.entryDate.year().eq(qAssetsDepreciation.checkDate.year())
                                .and(qAssets.entryDate.month().eq(qAssetsDepreciation.checkDate.month()))
                            ))
                        ));
        // 期间已生成凭证
        BlazeJPAQuery<Long> newGeneratedCol = bqf.selectFrom(qAssetsChange)
                .join(qAssets).on(qAssets.id.eq(qAssetsChange.assetsId).and(qAssetsChange.accountSetsId.eq(qAssets.accountSetsId)))
                .select(qAssetsChange.count())
                .where(qAssetsChange.accountSetsId.eq(accountSetsId)
                        .and(qAssetsChange.voucherId.isNotNull())
                        .and(qAssetsChange.changeType.eq(AssetsChangeType.新增))
                        .and(
                            // 有开始使用日期：开始使用日期的年月 = 计提期间的年月
                            (qAssets.startDate.isNotNull().and(
                                qAssets.startDate.year().eq(qAssetsDepreciation.checkDate.year())
                                .and(qAssets.startDate.month().eq(qAssetsDepreciation.checkDate.month()))
                            ))
                            // 没有开始使用日期：录入期间的年月 = 计提期间的年月
                            .or(qAssets.startDate.isNull().and(
                                qAssets.entryDate.year().eq(qAssetsDepreciation.checkDate.year())
                                .and(qAssets.entryDate.month().eq(qAssetsDepreciation.checkDate.month()))
                            ))
                        ));
        // 期间所有新增记录
        BlazeJPAQuery<Long> updateTotalNumCol = bqf.selectFrom(qAssetsChange)
                .select(qAssetsChange.count())
                .where(qAssetsChange.checkDate.eq(qAssetsDepreciation.checkDate)
                        .and(qAssetsChange.accountSetsId.eq(accountSetsId))
                        .and(qAssetsChange.changeType.in(AssetsChangeType.原值调整, AssetsChangeType.累计折旧调整)));
        // 期间已生成凭证
        BlazeJPAQuery<Long> updateGeneratedCol = bqf.selectFrom(qAssetsChange)
                .select(qAssetsChange.count())
                .where(qAssetsChange.checkDate.eq(qAssetsDepreciation.checkDate)
                        .and(qAssetsChange.voucherId.isNotNull())
                        .and(qAssetsChange.accountSetsId.eq(accountSetsId))
                        .and(qAssetsChange.changeType.in(AssetsChangeType.原值调整, AssetsChangeType.累计折旧调整)));

        LocalDate lastChecked = bqf.selectFrom(qAssetsDepreciation)
                .select(qAssetsDepreciation.checkDate)
                .where(qAssetsDepreciation.accountSetsId.eq(accountSetsId))
                .orderBy(qAssetsDepreciation.checkDate.desc(), qAssetsDepreciation.id.desc())
                .fetchFirst();

        PagedList<Tuple> pagedList = bqf.selectFrom(qAssetsDepreciation)
                .select(qAssetsDepreciation, qVoucher.code, qVoucher.word, newTotalNumCol, newGeneratedCol, updateTotalNumCol, updateGeneratedCol)
                .leftJoin(qVoucher).on(qVoucher.id.eq(qAssetsDepreciation.voucherId).and(qVoucher.accountSetsId.eq(accountSetsId)))
                .where(qAssetsDepreciation.accountSetsId.eq(accountSetsId))
                .orderBy(qAssetsDepreciation.checkDate.desc(), qAssetsDepreciation.id.desc())
                .fetchPage(page.getOffset(), page.getOffsetEnd());
        ArrayList<DepreciationDto> collect = pagedList.stream().collect(ArrayList::new, (list, tuple) -> {
            DepreciationDto depreciationDto = BeanUtil.toBean(tuple.get(qAssetsDepreciation), DepreciationDto.class);
            if (depreciationDto.getVoucherId() != null) {
                depreciationDto.setVoucherTitle(tuple.get(qVoucher.word) + "-" + tuple.get(qVoucher.code));
            } else if (depreciationDto.getStatus() == 4) {
                depreciationDto.setVoucherTitle("无需生成凭证");
            }

            Long newTotalNum = Optional.ofNullable(tuple.get(newTotalNumCol)).orElse(0L);
            Long newGenerated = Optional.ofNullable(tuple.get(newGeneratedCol)).orElse(0L);
            Long updateTotalNum = Optional.ofNullable(tuple.get(updateTotalNumCol)).orElse(0L);
            Long updateGenerated = Optional.ofNullable(tuple.get(updateGeneratedCol)).orElse(0L);

            if (newTotalNum > 0) {
                if (newTotalNum.equals(newGenerated)) {
                    depreciationDto.setNewStatus(2);
                } else {
                    depreciationDto.setNewStatus(1);
                }
            } else {
                depreciationDto.setNewStatus(0);
            }

            if (updateTotalNum > 0) {
                if (updateTotalNum.equals(updateGenerated)) {
                    depreciationDto.setUpdateStatus(2);
                } else {
                    depreciationDto.setUpdateStatus(1);
                }
            } else {
                depreciationDto.setUpdateStatus(0);
            }
            //最后一期不能取消
            if (depreciationDto.getCheckDate().equals(lastChecked)) {
                depreciationDto.setCancel(false);
            }
            list.add(depreciationDto);
        }, List::addAll);
        return new PageResults<>(collect, page, pagedList.getTotalSize());
    }

    @Transactional
    public List<Dict> generateVoucher(PrimaryKey primaryKey) {
        AssetsDepreciation depreciation = bqf.selectFrom(qAssetsDepreciation)
                .where(qAssetsDepreciation.id.eq(primaryKey.getId())
                        .and(qAssetsDepreciation.voucherId.isNull().and(qAssetsDepreciation.status.ne(4)))
                        .and(qAssetsDepreciation.accountSetsId.eq(primaryKey.getAccountSetsId())))
                .fetchFirst();

        List<Dict> voucherItems = new ArrayList<>();
        if (depreciation != null) {
            List<Tuple> tuples = bqf.selectFrom(qAssetsDetail)
                    .select(qAssets.code, qAssets.name, qAssetsDetail.depreciationCredit,
                            qAssets.totalDepreciationSubjectCode,
                            qAssets.depreciationExpenseSubjectCode)
                    .join(qAssets).on(qAssets.id.eq(qAssetsDetail.assertId).and(qAssetsDetail.accountSetsId.eq(qAssets.accountSetsId)))
                    .where(qAssetsDetail.depreciationId.eq(depreciation.getId())
                            .and(qAssetsDetail.accountSetsId.eq(primaryKey.getAccountSetsId())))
                    .fetch();

            if (CollUtil.isEmpty(tuples)) {
                depreciation.setStatus(4);
                depreciationRepository.save(depreciation);
            } else {
                Map<String, String> subjectCodeMap = subjectService.list(primaryKey.getAccountSetsId()).stream().collect(Collectors.toMap(SubjectVo::getCode, SubjectVo::getName));
                for (Tuple tuple : tuples) {
                    String assets = tuple.get(qAssets.code) + "-" + tuple.get(qAssets.name);
                    String depreciationExpenseSubjectCode = tuple.get(qAssets.depreciationExpenseSubjectCode);
                    String totalDepreciationSubjectCode = tuple.get(qAssets.totalDepreciationSubjectCode);

                    Assert.isTrue(depreciationExpenseSubjectCode != null && subjectCodeMap.containsKey(depreciationExpenseSubjectCode), assets + "资产未设置【折旧费用科目】，请检查资产信息~");
                    Assert.isTrue(totalDepreciationSubjectCode != null && subjectCodeMap.containsKey(totalDepreciationSubjectCode), assets + "资产未设置【累计折旧科目】，请检查资产信息~");

                    voucherItems.add(Dict.create()
                            .set("summary", "计提折旧-" + assets)
                            .set("assetsSubjectCode", depreciationExpenseSubjectCode)
                            .set("debitAmount", tuple.get(qAssetsDetail.depreciationCredit))
                    );
                    voucherItems.add(Dict.create()
                            .set("summary", "计提折旧-" + assets)
                            .set("assetsSubjectCode", tuple.get(qAssets.totalDepreciationSubjectCode))
                            .set("creditAmount", tuple.get(qAssetsDetail.depreciationCredit))
                    );
                }
            }
        }
        return voucherItems;
    }

    @Override
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        AssetsDepreciation assetsDepreciation = new AssetsDepreciation();
        assetsDepreciation.setAccountSetsId(accountSets.getId());
        assetsDepreciation.setCheckYear(accountSets.getCurrentAccountDate().getYear());
        assetsDepreciation.setCheckMonth(accountSets.getCurrentAccountDate().getMonthValue());
        assetsDepreciation.setStatus(1);
        assetsDepreciation.setCheckDate(accountSets.getCurrentAccountDate());
        depreciationRepository.save(assetsDepreciation);
    }

    @Override
    @Transactional
    public void clear(Long accountSetsId) {
        jqf.delete(qAssetsDepreciation)
                .where(qAssetsDepreciation.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Transactional
    public void relateVoucher(Long depreciationId, Long voucherId, Long accountSetId) {
        jqf.update(qAssetsDepreciation)
                .set(qAssetsDepreciation.voucherId, voucherId)
                .set(qAssetsDepreciation.cancel, false)
                .where(qAssetsDepreciation.id.eq(depreciationId)
                        .and(qAssetsDepreciation.accountSetsId.eq(accountSetId)))
                .execute();

        jqf.update(qAssetsDepreciation)
                .set(qAssetsDepreciation.cancel, false)
                .where(qAssetsDepreciation.id.lt(depreciationId)
                        .and(qAssetsDepreciation.accountSetsId.eq(accountSetId)))
                .execute();

        jqf.update(qAssets)
                .set(qAssets.useMonth, qAssets.useMonth.add(1))
                .set(qAssets.remainingUseMonth, qAssets.remainingUseMonth.subtract(1))
                .set(qAssets.totalDepreciation, qAssets.totalDepreciation.add(qAssets.currentMonthDepreciation))
                .where(qAssets.accountSetsId.eq(accountSetId).and(qAssets.id.in(bqf.selectFrom(qAssetsDetail)
                        .select(qAssetsDetail.assertId)
                        .where(qAssetsDetail.accountSetsId.eq(accountSetId)
                                .and(qAssetsDetail.depreciationId.eq(depreciationId))).fetch())))
                .execute();

        jqf.update(qAssetsDetail)
                .set(qAssetsDetail.voucherId, voucherId)
                .where(qAssetsDetail.depreciationId.eq(depreciationId))
                .execute();
    }

    public PageResults<Dict> detail(Query query) {
        LocalDate initCheckDate = bqf.selectFrom(qAssetsChange)
                .select(qAssetsChange.checkDate)
                .where(qAssetsChange.accountSetsId.eq(query.accountSetsId))
                .orderBy(qAssetsChange.checkDate.asc())
                .fetchFirst();
        org.sagacity.sqltoy.model.Page<Dict> dictPage = lazyDao.findPageBySql(query.lazyPage(), "depreciationDetail", BeanUtil.beanToMap(query), Dict.class);
        // 判断是否查询到期初年
        if (initCheckDate != null && query.getStart().getYear() == initCheckDate.getYear()) {
            // 期初本年累计
            Map<Long, BigDecimal> yearTotalDepreciation = bqf.selectFrom(qAssets)
                    .select(qAssets.id, qAssets.yearTotalDepreciation)
                    .where(qAssets.accountSetsId.eq(query.accountSetsId))
                    .fetch().stream().collect(Collectors.toMap(t -> t.get(qAssets.id), t -> Optional.ofNullable(t.get(qAssets.yearTotalDepreciation)).orElse(BigDecimal.ZERO)));
            for (Dict row : dictPage.getRows()) {
                if (row.getStr("month").startsWith(query.getStart().getYear() + "")) {
                    if (row.containsKey("assertId") && yearTotalDepreciation.containsKey(row.getLong("assertId"))) {
                        row.set("currentYear", row.getBigDecimal("currentYear").add(yearTotalDepreciation.get(row.getLong("assertId"))));
                    }
                }
            }
        }

        Map<String, List<Dict>> monthGroup = dictPage.getRows().stream().collect(Collectors.groupingBy(d -> d.getStr("month")));
        List<Dict> datas = new ArrayList<>();
        List<String> sumFields = List.of("originalValue", "current", "currentYear", "total", "depreciationCredit");
        monthGroup.forEach((month, list) -> {
            datas.addAll(list);
            Dict sumRow = Dict.create().set("month", month)
                    .set("typeName", "合计");
            for (String field : sumFields) {
                sumRow.set(field, list.parallelStream().map(d -> d.getBigDecimal(field)).reduce(BigDecimal::add).orElse(BigDecimal.ZERO));
            }
            datas.add(sumRow);
        });
        return new PageResults<>(datas, query.toPage(), dictPage.getRecordCount());
    }

    @Override
    public void voucherDeleted(Long voucherId, Long accountSetId) {
        AssetsDepreciation assetsDepreciation = jqf.selectFrom(qAssetsDepreciation)
                .where(qAssetsDepreciation.voucherId.eq(voucherId).and(qAssetsDepreciation.accountSetsId.eq(accountSetId)))
                .fetchFirst();
        if (assetsDepreciation != null) {
            jqf.update(qAssetsDepreciation)
                    .setNull(qAssetsDepreciation.voucherId)
                    .set(qAssetsDepreciation.cancel, true)
                    .set(qAssetsDepreciation.status, 2)
                    .where(qAssetsDepreciation.voucherId.eq(voucherId).and(qAssetsDepreciation.accountSetsId.eq(accountSetId)))
                    .execute();

            jqf.update(qAssets)
                    .set(qAssets.useMonth, qAssets.useMonth.subtract(1))
                    .set(qAssets.remainingUseMonth, qAssets.remainingUseMonth.add(1))
                    .set(qAssets.totalDepreciation, qAssets.totalDepreciation.subtract(qAssets.currentMonthDepreciation))
                    .where(qAssets.accountSetsId.eq(accountSetId).and(qAssets.id.in(bqf.selectFrom(qAssetsDetail)
                            .select(qAssetsDetail.assertId)
                            .where(qAssetsDetail.accountSetsId.eq(accountSetId)
                                    .and(qAssetsDetail.depreciationId.eq(assetsDepreciation.getId()))).fetch())))
                    .execute();
        }
    }

    @Override
    public String backup(Long accountSetsId) {
        List<AssetsDepreciation> depreciations = bqf.selectFrom(qAssetsDepreciation)
                .where(qAssetsDepreciation.accountSetsId.eq(accountSetsId)).fetch();
        return JSON.toJSONString(depreciations);
    }

    @Override
    public Class<?> backupType() {
        return AssetsDepreciation.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {
        List<AssetsDepreciation> entityList = JSON.parseArray(json, AssetsDepreciation.class);
        for (AssetsDepreciation entity : entityList) {
            entity.setAccountSetsId(accountSetsId);
            depreciationRepository.save(entity);
        }
    }

    /**
     * 查询指定账期内已计提（status=2）但未生成凭证的折旧记录。
     */
    public List<AssetsDepreciation> listByPeriodWithoutVoucher(Long accountSetsId, int year, int month) {
        return bqf.selectFrom(qAssetsDepreciation)
                .where(qAssetsDepreciation.accountSetsId.eq(accountSetsId)
                        .and(qAssetsDepreciation.checkYear.eq(year))
                        .and(qAssetsDepreciation.checkMonth.eq(month))
                        .and(qAssetsDepreciation.voucherId.isNull())
                        .and(qAssetsDepreciation.status.eq(2)))
                .fetch();
    }

    /**
     * 自动生成固定资产折旧凭证并保存，关联至折旧记录。
     * 仅处理已计提（status=2）且未关联凭证的记录。
     * REQUIRES_NEW：每条记录独立事务，单条失败不影响其他记录，也不污染外层事务。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void autoCreateVoucher(Long depreciationId, Long accountSetId) {
        // 1. 构建凭证条目（内部处理 status=4 情况）
        List<Dict> items = generateVoucher(new PrimaryKey(depreciationId, accountSetId));
        if (CollUtil.isEmpty(items)) return;

        // 2. 账套信息
        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(accountSetId))
                .fetchFirst();
        if (accountSets == null) throw new RuntimeException("账套不存在: " + accountSetId);

        // 3. 折旧期间末日作为凭证日期
        AssetsDepreciation depreciation = depreciationRepository.getReferenceById(
                new PrimaryKey(depreciationId, accountSetId));
        LocalDate voucherDate = depreciation.getCheckDate().with(TemporalAdjusters.lastDayOfMonth());

        // 4. 科目代码 → ID 映射
        Map<String, Long> codeToId = subjectService.list(accountSetId).stream()
                .collect(Collectors.toMap(SubjectVo::getCode, SubjectVo::getId, (a, b) -> a));

        // 5. 本位币 ID
        Long currencyId = currencyService.fetchLocal(accountSetId).getId();

        // 6. 默认凭证字
        VoucherWord vw = bqf.selectFrom(QVoucherWord.voucherWord)
                .where(QVoucherWord.voucherWord.accountSetsId.eq(accountSetId))
                .orderBy(QVoucherWord.voucherWord.id.asc())
                .fetchFirst();
        String word = vw != null ? vw.getWord() : "记";

        // 7. 凭证编号
        int code = voucherService.loadCode(accountSetId, word, voucherDate);

        // 8. 构建明细行
        List<VoucherDetailsDto> details = new ArrayList<>();
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (Dict item : items) {
            String subjectCode = item.getStr("assetsSubjectCode");
            Long subjectId = codeToId.get(subjectCode);
            if (subjectId == null) continue;

            VoucherDetailsDto d = new VoucherDetailsDto();
            d.setSubjectId(subjectId);
            d.setSummary(item.getStr("summary"));
            d.setCurrencyId(currencyId);
            BigDecimal debitAmt = item.getBigDecimal("debitAmount");
            BigDecimal creditAmt = item.getBigDecimal("creditAmount");
            if (debitAmt != null && debitAmt.compareTo(BigDecimal.ZERO) != 0) {
                d.setDebitAmount(debitAmt);
                totalDebit = totalDebit.add(debitAmt);
            } else if (creditAmt != null && creditAmt.compareTo(BigDecimal.ZERO) != 0) {
                d.setCreditAmount(creditAmt);
                totalCredit = totalCredit.add(creditAmt);
            }
            details.add(d);
        }
        if (details.isEmpty()) return;

        // 9. 保存凭证
        VoucherDto dto = new VoucherDto();
        dto.setWord(word);
        dto.setCode(code);
        dto.setVoucherDate(voucherDate);
        dto.setDebitAmount(totalDebit);
        dto.setCreditAmount(totalCredit);
        dto.setAccountSetsId(accountSetId);
        dto.setDetails(details);
        VoucherDto saved = voucherService.save(dto, accountSets, true);

        // 10. 关联凭证 → 同时更新 Assets.useMonth / totalDepreciation 等
        if (saved != null && saved.getId() != null) {
            relateVoucher(depreciationId, saved.getId(), accountSetId);
        }
    }

    public LocalDate getLast(Long accountSetsId) {
        return bqf.selectFrom(qAssetsDepreciation)
                .select(qAssetsDepreciation.checkDate)
                .where(qAssetsDepreciation.accountSetsId.eq(accountSetsId))
                .orderBy(qAssetsDepreciation.checkDate.desc())
                .limit(1).fetchFirst();
    }

    /**
     * 自动计提到当前记账日期
     * 从账套启用日期开始，逐月计提到当前记账日期
     * 如果某个期间有需要计提的固定资产，则执行计提
     *
     * @param accountSetsId 账套ID
     */
    @Transactional
    public void autoDepreciationToCurrentDate(Long accountSetsId) {
        // 获取账套的当前会计日期
        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(accountSetsId))
                .fetchFirst();
        if (accountSets == null) {
            return;
        }

        LocalDate currentAccountDate = accountSets.getCurrentAccountDate();
        if (currentAccountDate == null) {
            currentAccountDate = accountSets.getEnableDate();
        }

        // 将当前会计日期标准化为月份的第一天
        LocalDate currentMonth = currentAccountDate.withDayOfMonth(1);

        // 从账套启用日期开始
        LocalDate startMonth = accountSets.getEnableDate().withDayOfMonth(1);

        // 如果开始月份晚于当前月份，不需要计提
        if (startMonth.isAfter(currentMonth)) {
            return;
        }

        // 逐月计提到当前记账日期
        LocalDate monthToProcess = startMonth;
        while (!monthToProcess.isAfter(currentMonth)) {
            // 检查期间是否存在，如果不存在则创建
            AssetsDepreciation depreciation = bqf.selectFrom(qAssetsDepreciation)
                    .where(qAssetsDepreciation.accountSetsId.eq(accountSetsId)
                            .and(qAssetsDepreciation.checkDate.eq(monthToProcess)))
                    .fetchFirst();

            if (depreciation == null) {
                // 创建新的期间
                depreciation = new AssetsDepreciation();
                depreciation.setAccountSetsId(accountSetsId);
                depreciation.setCheckYear(monthToProcess.getYear());
                depreciation.setCheckMonth(monthToProcess.getMonthValue());
                depreciation.setStatus(1);
                depreciation.setCheckDate(monthToProcess);
                depreciation = depreciationRepository.save(depreciation);
            }

            // 如果期间状态为1（未计提），则执行计提
            if (depreciation.getStatus() == 1) {
                try {
                    depreciation(new PrimaryKey(depreciation.getId(), accountSetsId));
                } catch (Exception e) {
                    log.error("自动计提折旧失败，期间：{}", monthToProcess, e);
                    // 继续处理下一个期间，不中断流程
                }
            }

            monthToProcess = monthToProcess.plusMonths(1);
        }
    }

    /**
     * 确保从指定日期到当前会计日期的所有计提折旧期间都存在
     * 如果资产的开始使用日期（或录入期间）早于已有的计提折旧期间，自动创建缺失的期间
     *
     * @param accountSetsId 账套ID
     * @param startDate 开始日期（资产的开始使用日期或录入期间）
     */
    @Transactional
    public void ensurePeriodsExist(Long accountSetsId, LocalDate startDate) {
        if (startDate == null) {
            return;
        }

        // 获取账套的当前会计日期（直接查询，避免循环依赖）
        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(accountSetsId))
                .fetchFirst();
        if (accountSets == null) {
            return;
        }

        LocalDate currentAccountDate = accountSets.getCurrentAccountDate();
        if (currentAccountDate == null) {
            currentAccountDate = accountSets.getEnableDate();
        }

        // 将开始日期和当前会计日期标准化为月份的第一天
        LocalDate startMonth = startDate.withDayOfMonth(1);
        LocalDate endMonth = currentAccountDate.withDayOfMonth(1);

        // 如果开始日期晚于当前会计日期，不需要创建期间
        if (startMonth.isAfter(endMonth)) {
            return;
        }

        // 确保从开始日期到当前会计日期的所有期间都存在
        LocalDate currentMonth = startMonth;
        while (!currentMonth.isAfter(endMonth)) {
            // 检查期间是否已存在
            AssetsDepreciation existing = bqf.selectFrom(qAssetsDepreciation)
                    .where(qAssetsDepreciation.accountSetsId.eq(accountSetsId)
                            .and(qAssetsDepreciation.checkDate.eq(currentMonth)))
                    .fetchFirst();

            if (existing == null) {
                // 创建新的期间
                AssetsDepreciation newPeriod = new AssetsDepreciation();
                newPeriod.setAccountSetsId(accountSetsId);
                newPeriod.setCheckYear(currentMonth.getYear());
                newPeriod.setCheckMonth(currentMonth.getMonthValue());
                newPeriod.setStatus(1);
                newPeriod.setCheckDate(currentMonth);
                depreciationRepository.save(newPeriod);
            }

            currentMonth = currentMonth.plusMonths(1);
        }
    }

    @Data
    public static class Query {
        private int page;
        private int pageSize;
        private LocalDate start;
        private LocalDate end;
        private Long assetsTypeId;
        private Long accountSetsId;
        private String code;
        private Long deptId;

        public org.sagacity.sqltoy.model.Page<Dict> lazyPage() {
            return new org.sagacity.sqltoy.model.Page<>(pageSize, page);
        }

        public Page toPage() {
            return new Page().setSize(pageSize).setPage(page);
        }
    }
}


