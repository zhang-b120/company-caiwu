package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.AccountingCategoryDetailsCheck;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.AssetsChangeDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AssetsChangeType;
import cn.gson.financial.kernel.model.enums.AssetsDisposeType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.AssetsChangeRepository;
import cn.gson.financial.kernel.model.repo.AssetsDetailRepository;
import cn.gson.financial.kernel.model.repo.AssetsRepository;
import cn.gson.financial.kernel.model.vo.AssetsVo;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.blazebit.persistence.PagedList;
import com.blazebit.persistence.querydsl.BlazeJPAQuery;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.core.types.dsl.NumberPath;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : ${PACKAGE_NAME}</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年07月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetsService extends AbsService implements AccountSetsProcessor, BackupProcessor, AccountingCategoryDetailsCheck {

    private final AssetsRepository assetsRepository;

    private final AssetsChangeRepository assetsChangeRepository;

    private final AssetsDetailRepository assetsDetailRepository;

    private final AssetsDepreciationService assetsDepreciationService;

    private final QAssetsDetail qAssetsDetail = QAssetsDetail.assetsDetail;

    private final static QAssets qAssets = QAssets.assets;

    private final static QBalance qBalance = QBalance.balance;

    private final static QSubject qSubject = QSubject.subject;

    private final static QVoucher qVoucher = QVoucher.voucher;

    private final static QAssetsDepreciation qAssetsDepreciation = QAssetsDepreciation.assetsDepreciation;

    private static final QDepartment qDept = new QDepartment("dept");

    private final QAssetsChange qAssetsChange = QAssetsChange.assetsChange;

    private final QAssetsType qAssetsType = QAssetsType.assetsType;

    public PageResults<AssetsVo> list(Page page, AssetsQuery query, Long accountSetsId) {
        List<AssetsVo> datas = new ArrayList<>();
        //已经折旧月份
        BlazeJPAQuery<Long> detailCount = bqf.selectFrom(qAssetsDetail)
                .select(qAssetsDetail.count())
                .where(qAssetsDetail.assertId.eq(qAssets.id)
                        .and(qAssetsDetail.depreciationId.isNotNull().or(qAssetsDetail.changeId.isNotNull()))
                        .and(qAssetsDetail.accountSetsId.eq(accountSetsId)));

        // 累计折旧
        BlazeJPAQuery<BigDecimal> depreciationCreditSum = bqf.selectFrom(qAssetsDetail)
                .select(qAssetsDetail.depreciationCredit.sum())
                .where(qAssetsDetail.assertId.eq(qAssets.id)
                        .and(qAssetsDetail.depreciationId.isNotNull())
                        .and(qAssetsDetail.accountSetsId.eq(accountSetsId)));

        //变动次数
        BlazeJPAQuery<Long> voucherCount = bqf.selectFrom(qAssetsChange)
                .select(qAssetsChange.count())
                .where(qAssetsChange.assetsId.eq(qAssets.id)
                        .and(qAssetsChange.accountSetsId.eq(accountSetsId))
                        .and(qAssetsChange.voucherId.isNotNull()));

        BlazeJPAQuery<String> originalValueFiled = bqf.selectFrom(qAssetsChange)
                .select(qAssetsChange.afterChange)
                .where(qAssetsChange.accountSetsId.eq(accountSetsId)
                        .and(qAssetsChange.assetsId.eq(qAssets.id))
                        .and(qAssetsChange.changeType.in(AssetsChangeType.新增, AssetsChangeType.原值调整))
                        .and(qAssetsChange.checkDate.loe(query.getEntryDate())))
                .orderBy(qAssetsChange.checkDate.desc(), qAssetsChange.id.desc()).limit(1);

        PagedList<Tuple> fetchPage = bqf.selectFrom(qAssets).
                select(qAssets, qAssetsType.name, qDept.name, voucherCount, detailCount, depreciationCreditSum, originalValueFiled).
                leftJoin(qAssetsType).on(qAssets.assetsTypeId.eq(qAssetsType.id).and(qAssetsType.accountSetsId.eq(accountSetsId))).
                leftJoin(qDept).on(qAssets.deptId.eq(qDept.id).and(qDept.accountSetsId.eq(accountSetsId))).
                where(qAssets.accountSetsId.eq(accountSetsId).and(query.builder))
                .orderBy(qAssets.code.asc(), qAssets.id.desc())
                .fetchPage(page.getOffset(), page.getOffsetEnd());

        fetchPage.forEach(tuple -> {
            AssetsVo assetsVo = BeanUtil.toBean(tuple.get(qAssets), AssetsVo.class);
            assetsVo.setAssetsTypeName(tuple.get(qAssetsType.name));
            assetsVo.setDeptName(tuple.get(qDept.name));
            assetsVo.setIsVoucher(Optional.ofNullable(tuple.get(voucherCount)).orElse(0L) > 0);
            assetsVo.setDetailCount(tuple.get(detailCount));
            assetsVo.setDepreciationCreditSum(tuple.get(depreciationCreditSum));
            if (tuple.get(originalValueFiled) != null) {
                assetsVo.setOriginalValue(BigDecimal.valueOf(NumberUtil.parseDouble(tuple.get(originalValueFiled))));
            }
            datas.add(assetsVo);
        });

        List<Long> assetsIdList = datas.stream().map(AssetsVo::getId).collect(Collectors.toList());

        NumberExpression<BigDecimal> depreciationCredit = qAssetsDetail.depreciationCredit.sum().coalesce(BigDecimal.ZERO);
        //累计折旧
        Map<Long, BigDecimal> all = bqf.selectFrom(qAssetsDetail)
                .select(qAssetsDetail.assertId, depreciationCredit)
                .where(qAssetsDetail.accountSetsId.eq(accountSetsId)
                        .and(qAssetsDetail.checkDate.loe(query.entryDate))
                        .and(qAssetsDetail.assertId.in(assetsIdList)))
                .groupBy(qAssetsDetail.assertId)
                .fetch().stream().collect(Collectors.toMap(tuple -> tuple.get(qAssetsDetail.assertId), tuple -> Optional.ofNullable(tuple.get(depreciationCredit)).orElse(BigDecimal.ZERO)));

        //本期折旧
        Map<Long, BigDecimal> current = bqf.selectFrom(qAssetsDetail)
                .select(qAssetsDetail.assertId, depreciationCredit)
                .where(qAssetsDetail.accountSetsId.eq(accountSetsId)
                        .and(qAssetsDetail.checkDate.eq(query.entryDate))
                        .and(qAssetsDetail.assertId.in(assetsIdList)).and(qAssetsDetail.depreciationId.isNotNull()))
                .groupBy(qAssetsDetail.assertId)
                .fetch().stream().collect(Collectors.toMap(tuple -> tuple.get(qAssetsDetail.assertId), tuple -> Optional.ofNullable(tuple.get(depreciationCredit)).orElse(BigDecimal.ZERO)));

        datas.forEach(assetsVo -> {
            assetsVo.setCurrentDepreciation(current.getOrDefault(assetsVo.getId(), BigDecimal.ZERO));
            assetsVo.setTotalDepreciation(all.getOrDefault(assetsVo.getId(), BigDecimal.ZERO));
            assetsVo.setNetValue(NumberUtil.sub(assetsVo.getOriginalValue(), assetsVo.getTotalDepreciation()));
        });

        return new PageResults<>(datas, page, fetchPage.getTotalSize());
    }

    @Transactional
    public Assets save(Assets assets) {
        if (assets.getId() != null) {
            Assets original = assetsRepository.getReferenceById(assets.getPrimaryKey());
            if (!assets.getCode().equals(original.getCode())) {
                Assert.isTrue(bqf.selectFrom(qAssets)
                                      .where(qAssets.code.eq(assets.getCode())
                                              .and(qAssets.id.ne(assets.getId()))
                                              .and(qAssets.accountSetsId.eq(assets.getAccountSetsId())))
                                      .fetchCount() == 0, "编码已存在~");
            }
            
            // 保存修改前的开始使用日期和录入期间，用于判断是否需要重新创建期间
            LocalDate originalStartDate = original.getStartDate();
            LocalDate originalEntryDate = original.getEntryDate();
            LocalDate originalAssetStartDate = originalStartDate != null ? originalStartDate : originalEntryDate;
            
            BeanUtil.copyProperties(assets, original, CopyOptions.create().ignoreNullValue());
            assets = assetsRepository.save(original);

            // 检查开始使用日期或录入期间是否被修改
            LocalDate newStartDate = assets.getStartDate();
            LocalDate newEntryDate = assets.getEntryDate();
            LocalDate newAssetStartDate = newStartDate != null ? newStartDate : newEntryDate;
            
            // 如果开始使用日期（或录入期间）被修改，需要重新创建计提折旧期间
            if (newAssetStartDate != null && originalAssetStartDate != null) {
                // 将日期标准化为月份的第一天进行比较
                LocalDate originalMonth = originalAssetStartDate.withDayOfMonth(1);
                LocalDate newMonth = newAssetStartDate.withDayOfMonth(1);
                if (!originalMonth.equals(newMonth)) {
                    // 开始使用日期被修改，重新创建期间
                    assetsDepreciationService.ensurePeriodsExist(assets.getAccountSetsId(), newAssetStartDate);
                }
            } else if (newAssetStartDate != null && originalAssetStartDate == null) {
                // 从无开始使用日期变为有开始使用日期，创建期间
                assetsDepreciationService.ensurePeriodsExist(assets.getAccountSetsId(), newAssetStartDate);
            } else if (newAssetStartDate == null && originalAssetStartDate != null) {
                // 从有开始使用日期变为无开始使用日期，使用录入期间创建期间
                if (newEntryDate != null) {
                    assetsDepreciationService.ensurePeriodsExist(assets.getAccountSetsId(), newEntryDate);
                }
            }

            //是否已经生成凭证，未生成凭证修改了值需要同步更新detail
            if (!assets.getIsVoucher()) {
                jqf.update(qAssetsDetail)
                        .set(qAssetsDetail.originalDebit, assets.getOriginalValue())
                        .set(qAssetsDetail.depreciationCredit, assets.getTotalDepreciation())
                        .set(qAssetsDetail.depreciationBalance, assets.getTotalDepreciation())
                        .set(qAssetsDetail.netValue, NumberUtil.sub(assets.getOriginalValue(), assets.getTotalDepreciation()))
                        .where(qAssetsDetail.assertId.eq(assets.getId())
                                .and(qAssetsDetail.accountSetsId.eq(assets.getAccountSetsId()))
                                .and(qAssetsDetail.digest.eq("新增固定资产")))
                        .execute();
            }
        } else {
            Assert.isTrue(bqf.selectFrom(qAssets)
                                  .where(qAssets.code.eq(assets.getCode()).and(qAssets.accountSetsId.eq(assets.getAccountSetsId())))
                                  .fetchCount() == 0, "编码已存在~");
            assets.setStatus(1);
            assets = assetsRepository.save(assets);

            //变跟记录
            AssetsChange change = new AssetsChange();
            change.setChangeType(AssetsChangeType.新增);
            change.setAssetsId(assets.getId());
            change.setCheckDate(assets.getEntryDate());
            change.setCheckYear(assets.getEntryDate().getYear());
            change.setCheckMonth(assets.getEntryDate().getMonthValue());
            change.setAccountSetsId(assets.getAccountSetsId());
            change.setCreateDate(LocalDate.now());
            change.setAfterChange(assets.getOriginalValue() + "");
            change.setBeforeChange(assets.getOriginalValue() + "");
            assetsChangeRepository.save(change);

            //资产记录
            AssetsDetail assetsDetail = new AssetsDetail();
            assetsDetail.setAssertId(assets.getId());
            assetsDetail.setChangeId(change.getId());
            assetsDetail.setAccountSetsId(assets.getAccountSetsId());
            assetsDetail.setCheckDate(assets.getEntryDate());
            assetsDetail.setDigest("新增固定资产");
            assetsDetail.setOriginalDebit(assets.getOriginalValue());
            assetsDetail.setOriginalCredit(BigDecimal.ZERO);
            assetsDetail.setOriginalBalance(assets.getOriginalValue());
            assetsDetail.setDepreciationDebit(BigDecimal.ZERO);
            assetsDetail.setDepreciationCredit(assets.getTotalDepreciation());
            assetsDetail.setDepreciationBalance(assets.getTotalDepreciation());
            assetsDetail.setImpairmentDebit(BigDecimal.ZERO);
            assetsDetail.setImpairmentCredit(BigDecimal.ZERO);
            assetsDetail.setImpairmentBalance(BigDecimal.ZERO);
            assetsDetail.setUseState("正常");
            assetsDetail.setDeptName(assets.getDeptName());
            assetsDetail.setDeptId(assets.getDeptId());
            assetsDetail.setNetValue(NumberUtil.sub(assets.getOriginalValue(), assetsDetail.getDepreciationBalance()));
            assetsDetailRepository.save(assetsDetail);

            // 自动创建从资产的开始使用日期（或录入期间）到当前会计日期的所有计提折旧期间
            LocalDate assetStartDate = assets.getStartDate() != null ? assets.getStartDate() : assets.getEntryDate();
            if (assetStartDate != null) {
                assetsDepreciationService.ensurePeriodsExist(assets.getAccountSetsId(), assetStartDate);
            }
        }
        return assets;
    }

    @Transactional
    public void delete(Long assetsId, Long accountSetsId) {
        jqf.delete(qAssets).where(qAssets.id.eq(assetsId).and(qAssets.accountSetsId.eq(accountSetsId))).execute();
        jqf.delete(qAssetsChange).where(qAssetsChange.assetsId.eq(assetsId).and(qAssetsChange.accountSetsId.eq(accountSetsId))).execute();
        jqf.delete(qAssetsDetail).where(qAssetsDetail.assertId.eq(assetsId).and(qAssetsDetail.accountSetsId.eq(accountSetsId))).execute();
    }

    //资产变更记录列表
    public PageResults<AssetsChangeDto> changeList(Page page, Long accountSetsId) {
        List<AssetsChangeDto> datas = new ArrayList<>();
        PagedList<Tuple> fetchPage = bqf.selectFrom(qAssetsChange).
                leftJoin(qAssets).on(qAssets.id.eq(qAssetsChange.assetsId).and(qAssets.accountSetsId.eq(accountSetsId))).
                leftJoin(qDept).on(qDept.id.eq(qAssets.deptId).and(qDept.accountSetsId.eq(accountSetsId))).
                leftJoin(qVoucher).on(qVoucher.id.eq(qAssetsChange.voucherId).and(qVoucher.accountSetsId.eq(accountSetsId))).
                select(qVoucher.word, qVoucher.code, qAssets.name, qDept.name, qAssets.code, qAssets.assetsSubjectCode, qAssets.addType, qAssets.originalValue, qAssetsChange).
                where(qAssetsChange.accountSetsId.eq(accountSetsId))
                .orderBy(qAssetsChange.checkDate.desc(), qAssetsChange.id.desc())
                .fetchPage(page.getOffset(), page.getOffsetEnd());
        fetchPage.forEach(tuple -> {
            AssetsChangeDto changeVo = BeanUtil.toBean(tuple.get(qAssetsChange), AssetsChangeDto.class);
            changeVo.setAssetsName(tuple.get(qAssets.name));
            changeVo.setAssetsSubjectCode(tuple.get(qAssets.assetsSubjectCode));
            changeVo.setCode(tuple.get(qAssets.code));
            if (StrUtil.isNotEmpty(tuple.get(qVoucher.word))) {
                changeVo.setVoucher(tuple.get(qVoucher.word) + "-" + tuple.get(qVoucher.code));
            }
            changeVo.setOriginalValue(tuple.get(qAssets.originalValue));
            changeVo.setVoucherSummary(changeVo.getChangeType() + "资产_" + tuple.get(qDept.name) + "_" + tuple.get(qAssets.addType) + changeVo.getAssetsName() + changeVo.getCode());
            datas.add(changeVo);
        });
        return new PageResults<>(datas, page, fetchPage.getTotalSize());
    }

    //资产变更记录不保存
    @Transactional
    public void changeSave(AssetsChange entity) {
        if (entity.getPrimaryKey() != null) {
            AssetsChange original = assetsChangeRepository.getReferenceById(entity.getPrimaryKey());
            BeanUtil.copyProperties(entity, original, CopyOptions.create().ignoreNullValue());
            assetsChangeRepository.save(original);
        } else {
            AssetsDepreciation depreciation = getCheckDate(entity.getAccountSetsId());
            entity.setCheckDate(depreciation.getCheckDate());
            entity.setCheckYear(depreciation.getCheckDate().getYear());
            entity.setCheckMonth(depreciation.getCheckDate().getMonthValue());
            assetsChangeRepository.save(entity);
            Assets assets = bqf.selectFrom(qAssets)
                    .where(qAssets.id.eq(entity.getAssetsId()).and(qAssets.accountSetsId.eq(entity.getAccountSetsId())))
                    .fetchFirst();
            log.info("资产ID:{},AccountSetsId:{}", entity.getAssetsId(), entity.getAccountSetsId());
            Assert.notNull(assets, "资产信息错误~");

            AssetsDetail assetsDetail = null;
            switch (entity.getChangeType()) {
                case 原值调整 -> {
                    //资产记录
                    assetsDetail = new AssetsDetail();
                    assetsDetail.setAssertId(assets.getId());
                    assetsDetail.setChangeId(entity.getId());
                    assetsDetail.setAccountSetsId(assets.getAccountSetsId());
                    assetsDetail.setCheckDate(depreciation.getCheckDate());
                    assetsDetail.setDigest("原值调整");
                    assetsDetail.setOriginalDebit(BigDecimal.ZERO);
                    assetsDetail.setOriginalCredit(assets.getOriginalValue());
                    assetsDetail.setOriginalBalance(BigDecimal.valueOf(Double.parseDouble(entity.getAfterChange())));
                    assetsDetail.setDepreciationDebit(BigDecimal.ZERO);
                    assetsDetail.setDepreciationCredit(BigDecimal.ZERO);
                    assetsDetail.setDepreciationBalance(BigDecimal.ZERO);
                    assetsDetail.setImpairmentDebit(BigDecimal.ZERO);
                    assetsDetail.setImpairmentCredit(BigDecimal.ZERO);
                    assetsDetail.setImpairmentBalance(BigDecimal.ZERO);
                    assetsDetail.setUseState("正常");
                    assetsDetail.setDeptId(assets.getDeptId());
                    assetsDetail.setNetValue(assets.getOriginalValue());
                    assets.setOriginalValue(BigDecimal.valueOf(Double.parseDouble(entity.getAfterChange())));
                    assets.setCurrentMonthDepreciation(assets.currentMonthDepreciation(assets.getOriginalValue()));
                }
                case 累计折旧调整 -> {
                    //资产记录
                    assetsDetail = new AssetsDetail();
                    assetsDetail.setAssertId(assets.getId());
                    assetsDetail.setChangeId(entity.getId());
                    assetsDetail.setAccountSetsId(assets.getAccountSetsId());
                    assetsDetail.setCheckDate(depreciation.getCheckDate());
                    assetsDetail.setDepreciationId(depreciation.getId());
                    assetsDetail.setDigest("累计折旧调整");
                    assetsDetail.setOriginalDebit(BigDecimal.ZERO);
                    assetsDetail.setOriginalCredit(BigDecimal.ZERO);
                    assetsDetail.setOriginalBalance(BigDecimal.ZERO);
                    assetsDetail.setDepreciationDebit(BigDecimal.ZERO);
                    assetsDetail.setDepreciationCredit(BigDecimal.valueOf(Double.parseDouble(entity.getAfterChange())));
                    assetsDetail.setDepreciationBalance(BigDecimal.ZERO);
                    assetsDetail.setImpairmentDebit(BigDecimal.ZERO);
                    assetsDetail.setImpairmentCredit(BigDecimal.ZERO);
                    assetsDetail.setImpairmentBalance(BigDecimal.ZERO);
                    assetsDetail.setUseState("正常");
                    assetsDetail.setDeptId(assets.getDeptId());
                    assetsDetail.setNetValue(assets.getOriginalValue());
                    assets.setTotalDepreciation(BigDecimal.valueOf(Double.parseDouble(entity.getAfterChange())).add(assets.getTotalDepreciation()));
                }
                case 使用年限调整 -> assets.setExpectUseMonth(Integer.parseInt(entity.getAfterChange()));
                case 科目调整 -> {
                }
                case 部门转移 -> assets.setDeptId(Long.parseLong(entity.getAfterChange()));
                case 折旧方法调整 -> assets.setDepreciationMethod(entity.getAfterChange());
                case 状态修改 -> assets.setStatus(Integer.parseInt(entity.getAfterChange()));
            }
            if (assetsDetail != null) {
                assetsDetailRepository.save(assetsDetail);
            }
            assetsRepository.save(assets);
        }
    }

    //资产变更记录删除
    @Transactional
    public void changeDelete(PrimaryKey primaryKey) {
        assetsChangeRepository.deleteById(primaryKey);
    }

    @Transactional
    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qAssets).where(qAssets.accountSetsId.eq(accountSetsId)).execute();
        jqf.delete(qAssetsChange).where(qAssetsChange.accountSetsId.eq(accountSetsId)).execute();
        jqf.delete(qAssetsDetail).where(qAssetsDetail.accountSetsId.eq(accountSetsId)).execute();
    }

    @Transactional
    public void copy(Long[] copyList, Long accountSetsId) {
        List<Assets> assets = bqf.selectFrom(qAssets)
                .where(qAssets.accountSetsId.eq(accountSetsId).and(qAssets.id.in(copyList)))
                .fetch();
        String code = bqf.selectFrom(qAssets)
                .select(qAssets.code)
                .where(qAssets.accountSetsId.eq(accountSetsId))
                .orderBy(qAssets.createDateTime.desc())
                .fetchFirst();

        //取最后折旧期间
        LocalDate localDate = bqf.selectFrom(qAssetsDepreciation)
                .select(qAssetsDepreciation.checkDate)
                .where(qAssetsDepreciation.accountSetsId.eq(accountSetsId))
                .orderBy(qAssetsDepreciation.checkDate.desc(), qAssetsDepreciation.id.desc()).fetchFirst();

        for (Assets asset : assets) {
            bqf.detach(asset);
            Assets newAsset = BeanUtil.toBean(asset, Assets.class, CopyOptions.create().setIgnoreProperties("id"));
            code = getNextCode(code);
            newAsset.setCode(code);
            newAsset.setCreateDateTime(LocalDateTime.now());
            newAsset.setEntryDate(localDate);
            this.save(newAsset);
        }
    }

    public String getNextCode(String code) {
        String s1 = ReUtil.get("\\d+$", code, 0);
        String prefix = StrUtil.isEmpty(s1) ? code : code.lastIndexOf(s1) != -1 ? code.substring(0, code.lastIndexOf(s1)) : "";
        String next = (NumberUtil.parseInt(StrUtil.emptyToDefault(s1, "0")) + 1) + "";
        if (StrUtil.isNotEmpty(s1) && next.length() < s1.length()) {
            next = StrUtil.fillBefore(next, '0', s1.length());
        }
        return prefix + next;
    }

    @Transactional
    public void relateVoucher(Long voucherId, Long[] idList, Long accountSetsId) {
        jqf.update(qAssetsChange)
                .set(qAssetsChange.voucherId, voucherId)
                .where(qAssetsChange.id.in(idList).and(qAssetsChange.accountSetsId.eq(accountSetsId)))
                .execute();
        jqf.update(qAssetsDetail)
                .set(qAssetsDetail.voucherId, voucherId)
                .where(qAssetsDetail.changeId.in(idList).and(qAssetsDetail.accountSetsId.eq(accountSetsId)))
                .execute();
    }

    /**
     * 处置
     *
     * @param accountSetsId
     * @param assetsId
     * @param disposeType
     */
    @Transactional
    public void dispose(Long accountSetsId, Long assetsId, AssetsDisposeType disposeType) {
        AssetsDepreciation depreciation = this.getCheckDate(accountSetsId);

        Assets assets = bqf.selectFrom(qAssets)
                .where(qAssets.id.eq(assetsId).and(qAssets.accountSetsId.eq(accountSetsId)))
                .fetchFirst();
        assets.setDisposeType(disposeType);
        assets.setDisposeCheckDate(depreciation.getCheckDate());
        assetsRepository.save(assets);

        AssetsChange assetsChange = new AssetsChange();
        assetsChange.setChangeType(AssetsChangeType.资产处置);
        assetsChange.setAssetsId(assetsId);
        assetsChange.setCheckYear(depreciation.getCheckDate().getYear());
        assetsChange.setCheckMonth(depreciation.getCheckDate().getMonthValue());
        assetsChange.setCheckDate(depreciation.getCheckDate());
        assetsChange.setBeforeChange("正常");
        assetsChange.setAfterChange("已减少");
        assetsChange.setAccountSetsId(accountSetsId);

        assetsChangeRepository.save(assetsChange);

        //已折旧
        BigDecimal depreciationCredit = Optional.ofNullable(bqf.selectFrom(qAssetsDetail)
                .select(qAssetsDetail.depreciationCredit.sum())
                .where(qAssetsDetail.accountSetsId.eq(accountSetsId).and(qAssetsDetail.assertId.eq(assetsId)))
                .fetchFirst()).orElse(BigDecimal.ZERO);

        AssetsDetail assetsDetail = new AssetsDetail();
        assetsDetail.setCheckDate(depreciation.getCheckDate());
        assetsDetail.setAccountSetsId(accountSetsId);
        assetsDetail.setAssertId(assetsId);
        assetsDetail.setChangeId(assetsChange.getId());
        assetsDetail.setDigest("减少资产");
        assetsDetail.setOriginalDebit(BigDecimal.ZERO);
        assetsDetail.setOriginalCredit(assets.getOriginalValue());
        assetsDetail.setOriginalBalance(BigDecimal.ZERO);
        assetsDetail.setDepreciationDebit(depreciationCredit);
        assetsDetail.setDepreciationCredit(BigDecimal.ZERO);
        assetsDetail.setDepreciationBalance(BigDecimal.ZERO);
        assetsDetail.setImpairmentDebit(BigDecimal.ZERO);
        assetsDetail.setImpairmentCredit(BigDecimal.ZERO);
        assetsDetail.setImpairmentBalance(BigDecimal.ZERO);
        assetsDetail.setUseState("已减少");
        assetsDetail.setDeptId(assets.getDeptId());
        assetsDetail.setNetValue(BigDecimal.ZERO);

        assetsDetailRepository.save(assetsDetail);

        jqf.update(qAssetsDetail)
                .set(qAssetsDetail.useState, "已减少")
                .where(qAssetsDetail.assertId.eq(assetsId)
                        .and(qAssetsDetail.accountSetsId.eq(accountSetsId))
                        .and(qAssetsDetail.checkDate.eq(depreciation.getCheckDate())))
                .execute();
    }

    /**
     * 获取当前期间
     *
     * @param accountSetsId
     * @return
     */
    private AssetsDepreciation getCheckDate(Long accountSetsId) {
        return bqf.selectFrom(qAssetsDepreciation)
                .where(qAssetsDepreciation.accountSetsId.eq(accountSetsId))
                .orderBy(qAssetsDepreciation.checkDate.desc())
                .fetchFirst();
    }

    public List<Dict> checkDateList(Long accountSetId) {
        return bqf.selectFrom(qAssetsDepreciation)
                .select(qAssetsDepreciation.checkDate)
                .where(qAssetsDepreciation.accountSetsId.eq(accountSetId))
                .orderBy(qAssetsDepreciation.checkDate.desc())
                .fetch().stream().collect(ArrayList::new, (list, checkDate) -> {
                    list.add(Dict.create()
                            .set("key", checkDate)
                            .set("title", checkDate.format(DateTimeFormatter.ofPattern("yyyy年MM月"))));
                }, List::addAll);
    }

    public List<Dict> generateVoucher(Long[] idList, Long accountSetsId) {
        List<Tuple> tupleList = bqf.selectFrom(qAssetsChange)
                .select(qAssetsChange, qAssets, qDept.name)
                .leftJoin(qDept).on(qDept.id.eq(qAssets.deptId).and(qDept.accountSetsId.eq(qAssetsChange.accountSetsId)))
                .leftJoin(qAssets).on(qAssetsChange.assetsId.eq(qAssets.id).and(qAssetsChange.accountSetsId.eq(qAssets.accountSetsId)))
                .where(qAssetsChange.id.in(idList)
                        .and(qAssetsChange.voucherId.isNull())
                        .and(qAssetsChange.accountSetsId.eq(accountSetsId)))
                .fetch();


        List<Dict> voucherItems = new ArrayList<>();
        String kcxjCode = null, ljzjtxCode = null;
        for (Tuple tuple : tupleList) {
            AssetsChange change = tuple.get(qAssetsChange);
            Assets assets = tuple.get(qAssets);
            String deptName = tuple.get(qDept.name);

            switch (change.getChangeType()) {
                case 新增:
                    if (kcxjCode == null) {
                        kcxjCode = getSubjectCodeByName(accountSetsId, "银行存款");
                    }
                    voucherItems.add(Dict.create()
                            .set("summary", "新增资产_" + deptName + "_" + assets.getAddTypeVal() + assets.getCode())
                            .set("assetsSubjectCode", assets.getAssetsSubjectCode())
                            .set("debitAmount", assets.getOriginalValue())
                            .set("deptId", assets.getDeptId())
                    );
                    if (NumberUtil.isGreater(assets.getTotalDepreciation(), BigDecimal.ZERO)) {
                        voucherItems.add(Dict.create()
                                .set("summary", "新增资产_" + deptName + "_" + assets.getAddTypeVal() + assets.getCode())
                                .set("assetsSubjectCode", assets.getTotalDepreciationSubjectCode())
                                .set("creditAmount", assets.getTotalDepreciation())
                                .set("deptId", assets.getDeptId())
                        );
                    }
                    voucherItems.add(Dict.create()
                            .set("summary", "新增资产_" + deptName + "_" + assets.getAddTypeVal() + assets.getCode())
                            .set("assetsSubjectCode", kcxjCode)
                            .set("creditAmount", assets.getOriginalValue().subtract(assets.getTotalDepreciation()))
                            .set("deptId", assets.getDeptId())
                    );
                    break;
                case 原值调整:
                    double sub = NumberUtil.sub(change.getBeforeChange(), change.getAfterChange()).doubleValue();
                    voucherItems.add(Dict.create()
                            .set("summary", "原值调整_" + deptName + "_" + assets.getAddTypeVal() + assets.getCode())
                            .set("assetsSubjectCode", assets.getAssetsCleanSubjectCode())
                            .set(sub > 0 ? "debitAmount" : "creditAmount", Math.abs(sub))
                            .set("deptId", assets.getDeptId())
                    );
                    voucherItems.add(Dict.create()
                            .set("summary", "原值调整_" + deptName + "_" + assets.getAddTypeVal() + assets.getCode())
                            .set("assetsSubjectCode", assets.getAssetsSubjectCode())
                            .set(sub > 0 ? "creditAmount" : "debitAmount", Math.abs(sub))
                            .set("deptId", assets.getDeptId())
                    );
                    break;
                case 累计折旧调整:
                    if (ljzjtxCode == null) {
                        ljzjtxCode = getSubjectCodeByName(accountSetsId, "累计摊销");
                    }

                    voucherItems.add(Dict.create()
                            .set("summary", "累计折旧调整_" + deptName + "_" + assets.getAddTypeVal() + assets.getCode())
                            .set("assetsSubjectCode", ljzjtxCode)
                            .set("debitAmount", change.getAfterChange())
                            .set("deptId", assets.getDeptId())
                    );
                    voucherItems.add(Dict.create()
                            .set("summary", "累计折旧调整_" + deptName + "_" + assets.getAddTypeVal() + assets.getCode())
                            .set("assetsSubjectCode", assets.getTotalDepreciationSubjectCode())
                            .set("creditAmount", change.getAfterChange())
                            .set("deptId", assets.getDeptId())
                    );
                    break;
                case 资产处置:
                    NumberExpression<BigDecimal> depreciationCredit = qAssetsDetail.depreciationCredit.sum().coalesce(BigDecimal.ZERO);
                    //累计折旧
                    BigDecimal all = bqf.selectFrom(qAssetsDetail)
                            .select(depreciationCredit)
                            .where(qAssetsDetail.accountSetsId.eq(accountSetsId)
                                    .and(qAssetsDetail.assertId.eq(assets.getId())))
                            .groupBy(qAssetsDetail.assertId)
                            .fetchFirst();
                    voucherItems.add(Dict.create()
                            .set("summary", assets.getDisposeType().display + "资产_" + deptName + "_" + assets.getAddTypeVal() + assets.getCode())
                            .set("assetsSubjectCode", assets.getAssetsCleanSubjectCode())
                            .set("debitAmount", assets.getOriginalValue().subtract(all))
                            .set("deptId", assets.getDeptId())
                    );
                    if (NumberUtil.isGreater(all, BigDecimal.ZERO)) {
                        voucherItems.add(Dict.create()
                                .set("summary", assets.getDisposeType().display + "资产_" + deptName + "_" + assets.getAddTypeVal() + assets.getCode())
                                .set("assetsSubjectCode", assets.getTotalDepreciationSubjectCode())
                                .set("debitAmount", all)
                                .set("deptId", assets.getDeptId())
                        );
                    }
                    voucherItems.add(Dict.create()
                            .set("summary", assets.getDisposeType().display + "资产_" + deptName + "_" + assets.getAddTypeVal() + assets.getCode())
                            .set("assetsSubjectCode", assets.getAssetsSubjectCode())
                            .set("creditAmount", assets.getOriginalValue())
                            .set("deptId", assets.getDeptId())
                    );
                    break;
            }
        }
        return voucherItems;
    }

    public List<Dict> summary(Dict dict) {
        return lazyDao.findBySql(dict.getStr("type"), dict);
    }

    public List<Dict> check(Long accountSetsId, Long currencyId, AssetsQuery query) {
        SubjectResult result = getSubjectResult(accountSetsId, currencyId, query);

        AssetsResult assetsResult = getAssetsResult(accountSetsId, query);

        BigDecimal subjectBalance = result.initAssetsTotal().add(result.assetsDebit()).subtract(result.assetsCredit());
        BigDecimal assetBalance = assetsResult.initTotalOriginal().add(assetsResult.totalAssetsDebit().subtract(assetsResult.totalAssetsCredit()));

        BigDecimal depreciationBalance = result.initDepreciationTotal().add(result.depreciationCredit()).subtract(result.depreciationDebit());
        BigDecimal depreciationAssetBalance = assetsResult.initTotalDepreciation().add(assetsResult.depreciationCredit().subtract(assetsResult.depreciationDebit()));

        List<Dict> data = new ArrayList<>();
        data.add(Dict.create().set("type", "固定资产").set("id", 1));
        data.add(Dict.create()
                .set("type", "会计科目")
                .set("startBalance", result.initAssetsTotal())
                .set("balance", subjectBalance)
                .set("debit", result.assetsDebit())
                .set("credit", result.assetsCredit())
                .set("name", "固定资产").set("parentId", 1).set("id", 2));
        data.add(Dict.create()
                .set("type", "资产卡片")
                .set("startBalance", assetsResult.initTotalOriginal())
                .set("balance", assetBalance)
                .set("debit", assetsResult.totalAssetsDebit())
                .set("credit", assetsResult.totalAssetsCredit())
                .set("name", "资产原值").set("parentId", 1).set("id", 3));
        data.add(Dict.create().set("type", "差异")
                .set("startBalance", result.initAssetsTotal().subtract(assetsResult.initTotalOriginal()))
                .set("debit", result.assetsDebit().subtract(assetsResult.totalAssetsDebit()))
                .set("credit", result.assetsCredit().subtract(result.assetsCredit()))
                .set("balance", subjectBalance.subtract(assetBalance))
                .set("id", 7).set("parentId", 1));

        data.add(Dict.create().set("type", "累计折旧").set("id", 4));
        data.add(Dict.create()
                .set("type", "会计科目")
                .set("startBalance", result.initDepreciationTotal())
                .set("balance", depreciationBalance)
                .set("debit", result.depreciationDebit())
                .set("credit", result.depreciationCredit())
                .set("name", "累计折旧").set("parentId", 4).set("id", 5));
        data.add(Dict.create()
                .set("type", "资产卡片")
                .set("startBalance", assetsResult.initTotalDepreciation())
                .set("balance", depreciationAssetBalance)
                .set("debit", assetsResult.depreciationDebit())
                .set("credit", assetsResult.depreciationCredit())
                .set("name", "累计折旧").set("parentId", 4).set("id", 6));

        data.add(Dict.create().set("type", "差异")
                .set("startBalance", result.initDepreciationTotal().subtract(assetsResult.initTotalDepreciation()))
                .set("debit", result.depreciationDebit().subtract(assetsResult.depreciationDebit()))
                .set("credit", result.depreciationCredit().subtract(assetsResult.depreciationCredit()))
                .set("balance", depreciationBalance.subtract(depreciationAssetBalance))
                .set("id", 8).set("parentId", 4));

        return data;
    }

    @Override
    public String backup(Long accountSetsId) {
        List<Assets> assets = bqf.selectFrom(qAssets)
                .where(qAssets.accountSetsId.eq(accountSetsId)).fetch();
        return JSON.toJSONString(assets);
    }

    @Override
    public Class<?> backupType() {
        return Assets.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {
        List<Assets> assetsList = JSON.parseArray(json, Assets.class);
        for (Assets assets : assetsList) {
            assets.setAccountSetsId(accountSetsId);
            assetsRepository.save(assets);
        }
    }

    @Override
    public boolean isAccountingCategoryDetailsUsed(AcDetails acdId) {
        return bqf.selectFrom(qAssets)
                       .leftJoin(qDept).on(qDept.id.eq(qAssets.deptId).and(qDept.accountSetsId.eq(qAssets.accountSetsId)))
                       .where(qAssets.deptId.eq(acdId.getId())
                               .and(qAssets.accountSetsId.eq(acdId.getAccountSetsId())))
                       .fetchCount() > 0;
    }

    /**
     * 根据科目名称获取科目编码
     * @param accountSetsId
     * @param subjectName
     * @return
     */
    private String getSubjectCodeByName(Long accountSetsId, String subjectName) {
        String kcxjCode;
        Subject yhckSubject = bqf.selectFrom(QSubject.subject)
                .where(QSubject.subject.accountSetsId.eq(accountSetsId)
                        .and(QSubject.subject.name.eq(subjectName)))
                .fetchFirst();
        Assert.notNull(yhckSubject, "名称为[" + subjectName + "]的科目不存在，请检查科目~");
        // 如果不银行存款不是根科目，获取第一个根科目
        if (yhckSubject.getLeaf()) {
            kcxjCode = yhckSubject.getCode();
        } else {
            kcxjCode = bqf.selectFrom(QSubject.subject)
                    .select(QSubject.subject.code)
                    .where(QSubject.subject.accountSetsId.eq(accountSetsId)
                            .and(QSubject.subject.leaf.isTrue())
                            .and(QSubject.subject.code.startsWith(yhckSubject.getCode())))
                    .orderBy(QSubject.subject.queryCode.asc(), QSubject.subject.id.asc())
                    .fetchFirst();
        }
        return kcxjCode;
    }


    private AssetsResult getAssetsResult(Long accountSetsId, AssetsQuery query) {
        // 第一期期间
        LocalDate initCheckDate = bqf.selectFrom(qAssetsChange)
                .select(qAssetsChange.checkDate)
                .where(qAssetsChange.accountSetsId.eq(accountSetsId))
                .orderBy(qAssetsChange.checkDate.asc())
                .fetchFirst();

        // 期初资产卡片原值
        BigDecimal initTotalOriginal = bqf.selectFrom(qAssets)
                .select(bqf.selectFrom(qAssetsChange)
                        .select(qAssetsChange.afterChange.castToNum(BigDecimal.class).coalesce(BigDecimal.ZERO))
                        .where(qAssetsChange.accountSetsId.eq(accountSetsId)
                                .and(qAssetsChange.assetsId.eq(qAssets.id))
                                .and(qAssetsChange.changeType.in(AssetsChangeType.新增, AssetsChangeType.原值调整))
                                .and(qAssetsChange.checkDate.loe(query.getStart().minusMonths(1))))
                        .orderBy(qAssetsChange.checkDate.desc(), qAssetsChange.id.desc()).limit(1))
                .where(qAssets.accountSetsId.eq(accountSetsId))
                .fetch().stream().filter(Objects::nonNull).reduce(BigDecimal::add).orElse(BigDecimal.ZERO);

        // 期初资产卡片累计折旧
        BigDecimal initTotalDepreciation = Optional.ofNullable(bqf.selectFrom(qAssetsDetail)
                .select(qAssetsDetail.depreciationBalance.sum())
                .where(qAssetsDetail.accountSetsId.eq(accountSetsId)
                        .and(qAssetsDetail.checkDate.eq(query.getStart().minusMonths(1)))
                        .and(qAssetsDetail.depreciationId.isNotNull()))
                .fetchFirst()).orElse(BigDecimal.ZERO);

        // 本期资产卡片固定资产
        Tuple totalAssetsTuple = bqf.selectFrom(qAssetsDetail)
                .select(qAssetsDetail.originalDebit.sum(), qAssetsDetail.originalCredit.sum())
                .where(qAssetsDetail.accountSetsId.eq(accountSetsId)
                        .and(qAssetsDetail.checkDate.between(query.getStart(), query.getEnd()))
                        .and(qAssetsDetail.depreciationId.isNull()))
                .fetchFirst();

        BigDecimal totalAssetsDebit = totalAssetsTuple != null ? Optional.ofNullable(totalAssetsTuple.get(qAssetsDetail.originalDebit.sum())).orElse(BigDecimal.ZERO) : BigDecimal.ZERO;
        BigDecimal totalAssetsCredit = totalAssetsTuple != null ? Optional.ofNullable(totalAssetsTuple.get(qAssetsDetail.originalCredit.sum())).orElse(BigDecimal.ZERO) : BigDecimal.ZERO;

        // 本期资产卡片累计折旧
        Tuple totalDepreciationTuple = bqf.selectFrom(qAssetsDetail)
                .select(qAssetsDetail.depreciationDebit.sum(), qAssetsDetail.depreciationCredit.sum())
                .where(qAssetsDetail.accountSetsId.eq(accountSetsId)
                        .and(qAssetsDetail.checkDate.between(query.getStart(), query.getEnd()))
                        .and(qAssetsDetail.depreciationId.isNotNull()))
                .fetchFirst();

        BigDecimal depreciationDebit = totalDepreciationTuple != null ? Optional.ofNullable(totalDepreciationTuple.get(qAssetsDetail.depreciationDebit.sum())).orElse(BigDecimal.ZERO) : BigDecimal.ZERO;
        BigDecimal depreciationCredit = totalDepreciationTuple != null ? Optional.ofNullable(totalDepreciationTuple.get(qAssetsDetail.depreciationCredit.sum())).orElse(BigDecimal.ZERO) : BigDecimal.ZERO;

        if (initCheckDate != null && query.getStart().isEqual(initCheckDate)) {
            Tuple tuple = bqf.selectFrom(qAssetsDetail)
                    .select(qAssetsDetail.depreciationBalance.sum().coalesce(BigDecimal.ZERO), qAssetsDetail.depreciationCredit.sum())
                    .where(qAssetsDetail.accountSetsId.eq(accountSetsId)
                            .and(qAssetsDetail.checkDate.eq(query.getStart()))
                            .and(qAssetsDetail.depreciationId.isNotNull()))
                    .fetchFirst();
            if (tuple != null) {
                BigDecimal depreciationBalance = Optional.ofNullable(tuple.get(qAssetsDetail.depreciationBalance.sum().coalesce(BigDecimal.ZERO))).orElse(BigDecimal.ZERO);
                if (query.getStart().equals(query.getEnd())) {
                    depreciationCredit = depreciationBalance;
                } else {
                    depreciationCredit = depreciationBalance.subtract(Optional.ofNullable(tuple.get(qAssetsDetail.depreciationCredit.sum())).orElse(BigDecimal.ZERO)).add(depreciationCredit);
                }
            }
        }
        return new AssetsResult(initTotalOriginal, initTotalDepreciation, totalAssetsDebit, totalAssetsCredit, depreciationDebit, depreciationCredit);
    }

    /**
     * 资产检查
     * @param accountSetsId
     * @param year
     * @param month
     * @return
     */
    public boolean assetsCheck(Long accountSetsId, Integer year, Integer month) {
        long count = bqf.selectFrom(qAssets)
                .where(qAssets.accountSetsId.eq(accountSetsId)
                        .and(qAssets.status.eq(1))
                        .and(qAssets.remainingUseMonth.gt(0))
                        .and(qAssets.disposeType.isNull()))
                .fetchCount();
        if (count == 0) {
            return true;
        }

        AssetsDepreciation depreciation = bqf.selectFrom(qAssetsDepreciation)
                .where(qAssetsDepreciation.accountSetsId.eq(accountSetsId)
                        .and(qAssetsDepreciation.checkYear.eq(year))
                        .and(qAssetsDepreciation.checkMonth.eq(month)))
                .fetchFirst();
        // 变更记录 - 基于资产的开始使用日期（或录入期间）判断
        // 如果开始使用日期不为空，使用开始使用日期的年月；否则使用录入期间的年月
        long assetsChangeCount = bqf.selectFrom(qAssetsChange)
                .join(qAssets).on(qAssets.id.eq(qAssetsChange.assetsId).and(qAssetsChange.accountSetsId.eq(qAssets.accountSetsId)))
                .where(qAssetsChange.accountSetsId.eq(accountSetsId)
                        .and(qAssetsChange.voucherId.isNull())
                        .and(
                            // 对于新增资产，基于开始使用日期（或录入期间）判断
                            (qAssetsChange.changeType.eq(AssetsChangeType.新增).and(
                                // 有开始使用日期：开始使用日期的年月 = 检查期间的年月
                                (qAssets.startDate.isNotNull().and(
                                    qAssets.startDate.year().eq(year)
                                    .and(qAssets.startDate.month().eq(month))
                                ))
                                // 没有开始使用日期：录入期间的年月 = 检查期间的年月
                                .or(qAssets.startDate.isNull().and(
                                    qAssets.entryDate.year().eq(year)
                                    .and(qAssets.entryDate.month().eq(month))
                                ))
                            ))
                            // 对于其他变更类型（原值调整、累计折旧调整等），使用变更记录的期间
                            .or(qAssetsChange.changeType.ne(AssetsChangeType.新增).and(
                                qAssetsChange.checkYear.eq(year)
                                .and(qAssetsChange.checkMonth.eq(month))
                            ))
                        ))
                .fetchCount();
        // 本期有折旧记录，切折旧记录
        return depreciation != null
               && (depreciation.getStatus() == 4 || (depreciation.getStatus() == 2 && depreciation.getVoucherId() != null))
               && assetsChangeCount == 0;
    }

    private record AssetsResult(BigDecimal initTotalOriginal, BigDecimal initTotalDepreciation, BigDecimal totalAssetsDebit, BigDecimal totalAssetsCredit, BigDecimal depreciationDebit, BigDecimal depreciationCredit) {
    }

    /**
     * 资产科目总账
     * @param accountSetsId
     * @param currencyId
     * @param query
     * @return
     */
    private SubjectResult getSubjectResult(Long accountSetsId, Long currencyId, AssetsQuery query) {
        // 固定资产科目
        List<String> assetsSubjectCode = bqf.selectFrom(qAssets)
                .select(qAssets.assetsSubjectCode).distinct()
                .where(qAssets.accountSetsId.eq(accountSetsId))
                .fetch();
        // 累计折旧科目
        List<String> totalDepreciationSubjectCode = bqf.selectFrom(qAssets)
                .select(qAssets.totalDepreciationSubjectCode).distinct()
                .where(qAssets.accountSetsId.eq(accountSetsId))
                .fetch();
        // 固定资产本期累计
        Tuple assetsTotalTuple = bqf.selectFrom(qBalance)
                .select(qBalance.credit.sum(), qBalance.debit.sum())
                .join(qSubject).on(qSubject.id.eq(qBalance.subjectId).and(qBalance.accountSetsId.eq(qSubject.accountSetsId)))
                .where(qBalance.accountSetsId.eq(accountSetsId)
                        .and(qBalance.auxiliaryDetailId.eq(0L))
                        .and(qBalance.currencyId.eq(currencyId))
                        .and(qSubject.code.in(assetsSubjectCode))
                        .and(qBalance.date.between(query.start, query.end)))
                .groupBy(qBalance.subjectId)
                .fetchFirst();
        // 累计折旧本期累计
        Tuple depreciationTotalTuple = bqf.selectFrom(qBalance)
                .select(qBalance.credit.sum(), qBalance.debit.sum())
                .join(qSubject).on(qSubject.id.eq(qBalance.subjectId).and(qBalance.accountSetsId.eq(qSubject.accountSetsId)))
                .where(qBalance.accountSetsId.eq(accountSetsId)
                        .and(qBalance.auxiliaryDetailId.eq(0L))
                        .and(qBalance.currencyId.eq(currencyId))
                        .and(qSubject.code.in(totalDepreciationSubjectCode))
                        .and(qBalance.date.between(query.start, query.end)))
                .groupBy(qBalance.subjectId)
                .fetchFirst();
        // 固定资产期初
        BigDecimal initAssetsTotal = Optional.ofNullable(bqf.selectFrom(qBalance)
                .select(qBalance.beginBalance.sum())
                .join(qSubject).on(qSubject.id.eq(qBalance.subjectId).and(qBalance.accountSetsId.eq(qSubject.accountSetsId)))
                .where(qBalance.accountSetsId.eq(accountSetsId)
                        .and(qBalance.auxiliaryDetailId.eq(0L))
                        .and(qBalance.currencyId.eq(currencyId))
                        .and(qSubject.code.in(assetsSubjectCode))
                        .and(qBalance.date.eq(query.start)))
                .groupBy(qBalance.subjectId)
                .fetchFirst()).orElse(BigDecimal.ZERO);
        // 累计折旧期初
        BigDecimal initDepreciationTotal = Optional.ofNullable(bqf.selectFrom(qBalance)
                .select(qBalance.beginBalance.sum())
                .join(qSubject).on(qSubject.id.eq(qBalance.subjectId).and(qBalance.accountSetsId.eq(qSubject.accountSetsId)))
                .where(qBalance.accountSetsId.eq(accountSetsId)
                        .and(qBalance.auxiliaryDetailId.eq(0L))
                        .and(qBalance.currencyId.eq(currencyId))
                        .and(qSubject.code.in(totalDepreciationSubjectCode))
                        .and(qBalance.date.eq(query.start)))
                .groupBy(qBalance.subjectId)
                .fetchFirst()).orElse(BigDecimal.ZERO);

        BigDecimal assetsDebit = getAssetsVal(assetsTotalTuple, qBalance.debit);
        BigDecimal assetsCredit = getAssetsVal(assetsTotalTuple, qBalance.credit);
        BigDecimal depreciationDebit = getAssetsVal(depreciationTotalTuple, qBalance.debit);
        BigDecimal depreciationCredit = getAssetsVal(depreciationTotalTuple, qBalance.credit);
        return new SubjectResult(initAssetsTotal, initDepreciationTotal, assetsDebit, assetsCredit, depreciationDebit, depreciationCredit);
    }

    private record SubjectResult(BigDecimal initAssetsTotal, BigDecimal initDepreciationTotal, BigDecimal assetsDebit, BigDecimal assetsCredit, BigDecimal depreciationDebit, BigDecimal depreciationCredit) {
    }

    private static BigDecimal getAssetsVal(Tuple assetsTotalTuple, NumberPath<BigDecimal> qBalance) {
        return assetsTotalTuple != null ? Optional.ofNullable(assetsTotalTuple.get(qBalance.sum())).orElse(BigDecimal.ZERO) : BigDecimal.ZERO;
    }

    public static class AssetsQuery {
        final BooleanBuilder builder = new BooleanBuilder();
        @Getter
        @Setter
        public LocalDate start;
        @Getter
        @Setter
        public LocalDate end;
        @Getter
        @Setter
        public LocalDate entryDate;

        public void setCode(String code) {
            if (StrUtil.isNotEmpty(code)) {
                builder.and(qAssets.code.contains(code));
            }
        }

        public void setName(String name) {
            if (StrUtil.isNotEmpty(name)) {
                builder.and(qAssets.name.contains(name));
            }
        }

        public void setSpecification(String specification) {
            if (StrUtil.isNotEmpty(specification)) {
                builder.and(qAssets.specification.contains(specification));
            }
        }

        public void setSupplier(String supplier) {
            if (StrUtil.isNotEmpty(supplier)) {
                builder.and(qAssets.supplier.contains(supplier));
            }
        }

        public void setStatus(Integer status) {
            if (status != null) {
                builder.and(qAssets.status.eq(status));
            }
        }

        public void setDeptId(Long deptId) {
            if (deptId != null) {
                builder.and(qAssets.deptId.eq(deptId));
            }
        }


        public void setAssetsTypeId(Long assetsTypeId) {
            if (assetsTypeId != null) {
                builder.and(qAssets.assetsTypeId.eq(assetsTypeId));
            }
        }
    }
}