package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.common.PinYinUtil;
import cn.gson.financial.kernel.config.AccountingCategoryDetailsCheck;
import cn.gson.financial.kernel.config.AuxiliaryProcessor;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.AccountingCategoryDetailsRepository;
import cn.gson.financial.kernel.model.repo.AccountingCategoryRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.StrUtil;
import com.blazebit.persistence.PagedList;
import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
@AllArgsConstructor
public class AccountingCategoryDetailsService extends AbsService implements AuxiliaryDataService {

    private AccountingCategoryRepository accountingCategoryRepository;

    private List<AccountingCategoryDetailsCheck> accountingCategoryDetailsChecks;

    private AccountingCategoryDetailsRepository accountingCategoryDetailsRepository;

    private final List<AuxiliaryProcessor> auxiliaryProcessors;

    private final QAccountingCategory qAccountingCategory = QAccountingCategory.accountingCategory;

    private final QAccountingCategoryDetails qAccountingCategoryDetails = QAccountingCategoryDetails.accountingCategoryDetails;

    public void batchInsert(List<AccountingCategoryDetails> list) {
        accountingCategoryDetailsRepository.saveAll(list);
    }

    /**
     * @param accountSetsId
     * @param value
     * @return
     */
    public List<AccountingCategoryDetails> getByCodeSet(String name, Long accountSetsId, Set<String> value) {
        return bqf.selectFrom(qAccountingCategoryDetails)
                .leftJoin(qAccountingCategory).on(qAccountingCategory.id.eq(qAccountingCategoryDetails.categoryId))
                .where(qAccountingCategoryDetails.name.eq(name)
                        .and(qAccountingCategory.accountSetsId.eq(accountSetsId))
                        .and(qAccountingCategoryDetails.code.in(value)))
                .fetch();
    }

    /**
     * 导入数据
     *
     * @param categoryId
     * @param detailsMap
     * @return
     */
    @Transactional
    public Map<String, Integer> importData(Long accountSetsId, Long categoryId, AccountingCategoryType categoryType, Map<String, AcDetails> detailsMap) {
        Map<String, Integer> result = new HashMap<>(4);
        result.put("total", detailsMap.size());
        //判断是否已经在系统存在
        //已存在的数据
        List<AccountingCategoryDetails> existing = bqf.selectFrom(qAccountingCategoryDetails)
                .where(qAccountingCategoryDetails.categoryId.eq(categoryId)
                        .and(qAccountingCategoryDetails.accountSetsId.eq(accountSetsId))
                        .and(qAccountingCategoryDetails.code.in(detailsMap.keySet())))
                .fetch();
        result.put("updated", existing.size());
        List<AccountingCategoryDetails> updated = new ArrayList<>(existing.size());
        for (AccountingCategoryDetails categoryDetails : existing) {
            AccountingCategoryDetails d = (AccountingCategoryDetails) detailsMap.get(categoryDetails.getCode());
            if (d != null) {
                d.setId(categoryDetails.getId());
                updated.add(d);
            }
        }

        //新插入的
        List<AccountingCategoryDetails> inserted = detailsMap.values().stream().filter(d -> d.getId() == null)
                .map(d -> (AccountingCategoryDetails) d)
                .collect(Collectors.toList());
        result.put("inserted", inserted.size());

        if (!inserted.isEmpty()) {
            this.batchInsert(inserted);
        }

        if (!updated.isEmpty()) {
            accountingCategoryDetailsRepository.saveAll(updated);
        }
        return result;
    }

    @Override
    public String loadMaxCode(Long accountSetsId, Long categoryId) {
        return bqf.selectFrom(qAccountingCategoryDetails)
                .select(qAccountingCategoryDetails.code)
                .where(qAccountingCategoryDetails.categoryId.eq(categoryId)
                        .and(qAccountingCategory.accountSetsId.eq(accountSetsId)))
                .orderBy(qAccountingCategoryDetails.code.castToNum(Integer.class).desc(), qAccountingCategoryDetails.id.desc())
                .fetchFirst();
    }

    @Transactional
    public AccountingCategoryDetails save(AccountingCategoryDetails entity) {
        entity.setMnemonics(PinYinUtil.getFirstLettersLo(entity.getName()));
        BooleanExpression expression = qAccountingCategoryDetails.categoryId.eq(entity.getCategoryId())
                .and(qAccountingCategoryDetails.accountSetsId.eq(entity.getAccountSetsId()))
                .and(qAccountingCategoryDetails.code.eq(entity.getCode().trim()));

        if (entity.getId() != null) {
            expression = expression.and(qAccountingCategoryDetails.id.ne(entity.getId()));
        }
        if (bqf.selectFrom(qAccountingCategoryDetails).where(expression).fetchCount() > 0) {
            throw new ServiceException("亲，提交失败啦，编码已经存在！");
        }
        entity.setCategoryType(AccountingCategoryType.CUSTOMIZATION);
        accountingCategoryDetailsRepository.saveAndFlush(entity);
        //设置类别为不能编辑
        jqf.update(qAccountingCategory)
                .set(qAccountingCategory.canEdit, false)
                .where(qAccountingCategory.id.eq(entity.getCategoryId())
                        .and(qAccountingCategory.accountSetsId.eq(entity.getAccountSetsId())))
                .execute();
        return entity;
    }

    public PageResults<AccountingCategoryDetails> page(Page page, PrimaryKey categoryId, String keyword) {
        BooleanExpression expression = qAccountingCategoryDetails.categoryId.eq(categoryId.getId())
                .and(qAccountingCategoryDetails.accountSetsId.eq(categoryId.getAccountSetsId()));
        if (StrUtil.isNotEmpty(keyword)) {
            expression = expression.and(qAccountingCategoryDetails.code.contains(keyword).or(qAccountingCategoryDetails.name.contains(keyword)));
        }
        PagedList<AccountingCategoryDetails> fetchPage = bqf.selectFrom(qAccountingCategoryDetails)
                .where(expression)
                .orderBy(qAccountingCategoryDetails.code.castToNum(Integer.class).asc(), qAccountingCategoryDetails.id.desc())
                .fetchPage(page.getOffset(), page.getOffsetEnd());

        return new PageResults<>(fetchPage, page);
    }

    @Transactional
    public void delete(PrimaryKey categoryDetailsId) {
        AccountingCategoryDetails details = accountingCategoryDetailsRepository.getReferenceById(categoryDetailsId);
        Assert.isTrue(accountingCategoryDetailsChecks.stream().noneMatch(s -> s.isAccountingCategoryDetailsUsed(details)), "已被使用，不能删除，请检查期初，凭证，资产或资金模块~");
        accountingCategoryDetailsRepository.delete(details);

        this.delRelate(categoryDetailsId, details);

        long count = bqf.selectFrom(qAccountingCategoryDetails)
                .where(qAccountingCategoryDetails.categoryId.eq(details.getCategoryId())
                        .and(qAccountingCategoryDetails.accountSetsId.eq(details.getAccountSetsId())))
                .fetchCount();

        jqf.update(qAccountingCategory)
                .set(qAccountingCategory.canEdit, count == 0)
                .where(qAccountingCategory.id.eq(details.getCategoryId())
                        .and(qAccountingCategory.accountSetsId.eq(details.getAccountSetsId())))
                .execute();
    }

    public Map<String, List<AccountingCategoryDetails>> loadAuxiliaryAccountingDataByName(Long accountSetsId, List<AccountingCategoryType> nameList) {
        Map<String, List<AccountingCategoryDetails>> dataMap = new HashMap<>();
        for (AccountingCategoryType name : nameList) {
            dataMap.put(name.name(), new ArrayList<>());
        }
        bqf.selectFrom(qAccountingCategoryDetails)
                .select(qAccountingCategoryDetails, qAccountingCategory.name)
                .leftJoin(qAccountingCategory).on(qAccountingCategory.id.eq(qAccountingCategoryDetails.categoryId))
                .where(qAccountingCategory.accountSetsId.eq(accountSetsId).and(qAccountingCategory.categoryType.in(nameList)).and(qAccountingCategoryDetails.enable.isTrue()))
                .orderBy(qAccountingCategoryDetails.code.castToNum(Integer.class).asc(), qAccountingCategory.id.desc())
                .fetch().forEach(tuple -> {
                    dataMap.get(tuple.get(qAccountingCategory.name)).add(tuple.get(qAccountingCategoryDetails));
                });
        return dataMap;
    }

    public List<AccountingCategoryDetails> loadAuxiliaryAccountingDataByName(Long accountSetsId, String name) {
        return bqf.selectFrom(qAccountingCategoryDetails)
                .leftJoin(qAccountingCategory).on(qAccountingCategory.id.eq(qAccountingCategoryDetails.categoryId))
                .where(qAccountingCategory.accountSetsId.eq(accountSetsId).and(qAccountingCategory.name.eq(name)).and(qAccountingCategoryDetails.enable.isTrue()))
                .orderBy(qAccountingCategoryDetails.code.castToNum(Integer.class).asc(), qAccountingCategory.id.desc())
                .fetch();
    }

    @Override
    @Transactional
    public void clear(Long categoryId, Long accountSetsId) {
        AccountingCategory category = accountingCategoryRepository.getReferenceById(new PrimaryKey(categoryId, accountSetsId));
        if (!category.getAccountSetsId().equals(accountSetsId)) {
            throw new ServiceException("亲，您不能做清空操作！");
        }
        List<AccountingCategoryDetails> details = bqf.selectFrom(qAccountingCategoryDetails)
                .where(qAccountingCategoryDetails.accountSetsId.eq(accountSetsId)
                        .and(qAccountingCategoryDetails.categoryId.eq(categoryId)))
                .fetch();
        for (AccountingCategoryDetails detail : details) {
            try {
                this.delete(new PrimaryKey(detail.getId(), accountSetsId));
            } catch (Exception e) {
                log.info("accountSetsId:{},categoryId:{},detailId：{},已经被使用不能删除～", accountSetsId, categoryId, detail.getId());
            }
        }

        //设置为可以编辑
        jqf.update(qAccountingCategory)
                .set(qAccountingCategory.canEdit, true)
                .where(qAccountingCategory.id.eq(categoryId)
                        .and(qAccountingCategory.accountSetsId.eq(accountSetsId)))
                .execute();
    }

    @Override
    @Transactional
    public AccountingCategoryDetails save(AcDetails entity) {
        entity.setMnemonics(PinYinUtil.getFirstLettersLo(entity.getName()));
        if (entity.getId() == null) {
            entity.setId(snowflakeId.generate());
        }
        return accountingCategoryDetailsRepository.save(BeanUtil.toBean(entity, AccountingCategoryDetails.class));
    }

    @Override
    public List<AccountingCategoryDetails> loadData(Long accountSetsId, Long categoryId) {
        return loadData(accountSetsId, categoryId, null);
    }

    @Override
    public List<AccountingCategoryDetails> loadData(Long accountSetsId, Long categoryId, String filter) {
        BooleanExpression expression = qAccountingCategoryDetails.accountSetsId.eq(accountSetsId)
                .and(qAccountingCategoryDetails.categoryId.eq(categoryId));
        if (StrUtil.isNotEmpty(filter)) {
            expression = expression.and(qAccountingCategoryDetails.code.contains(filter).or(qAccountingCategoryDetails.name.contains(filter)));
        }
        return bqf.selectFrom(qAccountingCategoryDetails)
                .where(expression)
                .orderBy(qAccountingCategoryDetails.code.asc(), qAccountingCategoryDetails.id.asc())
                .fetch();
    }

    @Override
    public AcDetails loadByCode(Long accountSetsId, Long categoryId, String code) {
        return bqf.selectFrom(qAccountingCategoryDetails)
                .where(qAccountingCategoryDetails.code.eq(code)
                        .and(qAccountingCategoryDetails.categoryId.eq(categoryId))
                        .and(qAccountingCategoryDetails.accountSetsId.eq(accountSetsId)))
                .fetchFirst();
    }

    @Override
    public AcDetails loadByName(Long accountSetsId, Long categoryId, String name) {
        return bqf.selectFrom(qAccountingCategoryDetails)
                .where(qAccountingCategoryDetails.name.eq(name)
                        .and(qAccountingCategoryDetails.categoryId.eq(categoryId))
                        .and(qAccountingCategoryDetails.accountSetsId.eq(accountSetsId)))
                .fetchFirst();
    }

    @Override
    public Optional<AcDetails> loadById(Long accountSetsId, Long categoryId, Long id) {
        if (categoryId == null || id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(bqf.selectFrom(qAccountingCategoryDetails)
                .where(qAccountingCategoryDetails.id.eq(id)
                        .and(qAccountingCategoryDetails.categoryId.eq(categoryId))
                        .and(qAccountingCategoryDetails.accountSetsId.eq(accountSetsId)))
                .fetchFirst());
    }

    @Override
    public PageResults<AccountingCategoryDetails> loadPageData(Long accountSetsId, Long categoryId, Page page) {
        return loadPageData(accountSetsId, categoryId, page, null);
    }

    @Override
    public PageResults<AccountingCategoryDetails> loadPageData(Long accountSetsId, Long categoryId, Page page, String filter) {
        BooleanExpression expression = qAccountingCategoryDetails.accountSetsId.eq(accountSetsId)
                .and(qAccountingCategoryDetails.categoryId.eq(categoryId));
        if (StrUtil.isNotEmpty(filter)) {
            expression = expression.and(qAccountingCategoryDetails.code.contains(filter).or(qAccountingCategoryDetails.name.contains(filter)));
        }
        PagedList<AccountingCategoryDetails> pagedList = bqf.selectFrom(qAccountingCategoryDetails)
                .where(expression)
                .orderBy(qAccountingCategoryDetails.code.asc(), qAccountingCategoryDetails.id.asc())
                .fetchPage(page.getOffset(), page.getOffsetEnd());
        return new PageResults<>(pagedList, page);
    }

    @Override
    public Map<Long, AccountingCategoryDetails> loadDataByIdList(Long accountSetsId, Long categoryId, Set<Long> detailsIdList) {
        return loadListByIdList(accountSetsId, categoryId, detailsIdList).stream().collect(Collectors.toMap(AcDetails::getId, d -> d));
    }

    @Override
    public Map<Long, AccountingCategoryDetails> loadDataByIdList(Long accountSetsId, Set<Long> detailsIdList) {
        detailsIdList = detailsIdList.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (CollUtil.isEmpty(detailsIdList)) {
            return new HashMap<>();
        }
        return bqf.selectFrom(qAccountingCategoryDetails)
                .where(qAccountingCategoryDetails.accountSetsId.eq(accountSetsId)
                        .and(qAccountingCategoryDetails.id.in(detailsIdList)))
                .orderBy(qAccountingCategoryDetails.code.asc(), qAccountingCategoryDetails.id.asc())
                .fetch().stream().collect(Collectors.toMap(AcDetails::getId, d -> d));
    }

    @Override
    public List<AccountingCategoryDetails> loadListByIdList(Long accountSetsId, Long categoryId, Set<Long> detailsIdList) {
        return bqf.selectFrom(qAccountingCategoryDetails)
                .where(qAccountingCategoryDetails.accountSetsId.eq(accountSetsId)
                        .and(qAccountingCategoryDetails.categoryId.eq(categoryId))
                        .and(qAccountingCategoryDetails.id.in(detailsIdList)))
                .orderBy(qAccountingCategoryDetails.code.asc(), qAccountingCategoryDetails.id.asc())
                .fetch();
    }

    @Override
    public List<AccountingCategoryDetails> loadListByIdList(Long accountSetsId, Set<Long> detailsIdList) {
        return bqf.selectFrom(qAccountingCategoryDetails)
                .where(qAccountingCategoryDetails.accountSetsId.eq(accountSetsId)
                        .and(qAccountingCategoryDetails.id.in(detailsIdList)))
                .orderBy(qAccountingCategoryDetails.code.asc(), qAccountingCategoryDetails.id.asc())
                .fetch();
    }


    public void delRelate(PrimaryKey primaryKey, AcDetails reference) {
        //查询出所有关联辅助
        List<Dict> ffadList = lazyDao.findBySql("ab-auxiliaryIdList", Dict.create()
                .set("accountSetsId", primaryKey.getAccountSetsId())
                .set("detailId", primaryKey.getId())
                .set("categoryId", reference.getCategoryId())
                .set("categoryType", reference.getCategoryType()), Dict.class);

        if (CollUtil.isNotEmpty(ffadList)) {
            List<Long> detailIds = ffadList.stream().map(d -> d.getLong("id")).toList();
            Long count = lazyDao.getCount("voucherDetailsAuxiliaryCount", Dict.create()
                    .set("accountSetsId", primaryKey.getAccountSetsId())
                    .set("detailIds", detailIds));
            Assert.isTrue(count == 0L, "已被凭证使用，不能删除~");

            QAuxiliaryDetail qAuxiliaryDetail = QAuxiliaryDetail.auxiliaryDetail;
            QVoucherDetails qVoucherDetails = QVoucherDetails.voucherDetails;

            List<AuxiliaryDetail> details = bqf.selectFrom(qAuxiliaryDetail)
                    .where(qAuxiliaryDetail.accountSetsId.eq(primaryKey.getAccountSetsId())
                            .and(qAuxiliaryDetail.id.in(detailIds)))
                    .fetch();
            for (AuxiliaryDetail detail : details) {
                List<AuxiliaryDetail.Detail> list = detail.getDetails().stream().filter(d -> !(d.getCategoryId().equals(reference.getCategoryId()) && d.getValue().equals(primaryKey.getId()))).toList();
                if (CollUtil.isEmpty(list)) {
                    //如果没有辅助项目了，就删除这个辅助组合
                    jqf.delete(qAuxiliaryDetail)
                            .where(qAuxiliaryDetail.id.eq(detail.getId()).and(qAuxiliaryDetail.accountSetsId.eq(primaryKey.getAccountSetsId())))
                            .execute();
                } else {
                    //构建辅助项id组合键
                    String calCol = detail.getDetails().stream()
                            .sorted(Comparator.comparing(AuxiliaryDetail.Detail::getCategoryId))
                            .map(s -> String.valueOf(s.getValue()))
                            .collect(Collectors.joining("_"));
                    //查询组合键是否存在
                    Long detailId = bqf.selectFrom(qAuxiliaryDetail)
                            .select(qAuxiliaryDetail.id)
                            .where(qAuxiliaryDetail.calCol.eq(calCol).and(qAuxiliaryDetail.accountSetsId.eq(primaryKey.getAccountSetsId())))
                            .fetchFirst();

                    if (detailId != null) {
                        //如果已经存在组合，则把关联更新未已存在的再删除自己
                        jqf.update(qVoucherDetails)
                                .set(qVoucherDetails.auxiliaryDetailId, detailId)
                                .where(qVoucherDetails.accountSetsId.eq(primaryKey.getAccountSetsId()))
                                .execute();
                    }

                    jqf.delete(qAuxiliaryDetail)
                            .where(qAuxiliaryDetail.id.eq(detail.getId()).and(qAuxiliaryDetail.accountSetsId.eq(primaryKey.getAccountSetsId())))
                            .execute();
                }
            }
        }

        for (AuxiliaryProcessor processor : auxiliaryProcessors) {
            processor.deleted(primaryKey, reference);
        }
    }
}

