package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.dto.AccountingCategoryDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.mapper.AccountingCategoryMapper;
import cn.gson.financial.kernel.model.repo.AccountingCategoryDetailsRepository;
import cn.gson.financial.kernel.model.repo.AccountingCategoryRepository;
import cn.gson.financial.kernel.model.repo.AuxiliaryDetailRepository;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Dict;
import cn.hutool.extra.spring.SpringUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>******************************************************private final cn.gson.financial.kernel.repo.CashierAccountRepository cashierAccountRepository;**********************</p>
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : ${PACKAGE_NAME}</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年07月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountingCategoryService extends AbsService implements AccountSetsProcessor, BackupProcessor {

    private final AccountingCategoryRepository accountingCategoryRepository;
    private final AccountingCategoryDetailsRepository accountingCategoryDetailsRepository;
    private final AuxiliaryDetailRepository auxiliaryDetailRepository;
    private final QAccountingCategory qAccountingCategory = QAccountingCategory.accountingCategory;
    private final QAccountingCategoryDetails qAccountingCategoryDetails = QAccountingCategoryDetails.accountingCategoryDetails;
    private final AccountingCategoryMapper accountingCategoryMapper;

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "accountingCategory", key = "#entity.accountSetsId"),
            @CacheEvict(value = "accountingCategoryEntity", key = "#entity.primaryKey", condition = "#entity.id != null")
    })
    public void save(AccountingCategory entity) {
        if (accountingCategoryRepository.count(qAccountingCategory.accountSetsId.eq(entity.getAccountSetsId())
                .and(qAccountingCategory.name.eq(entity.getName().trim()))) > 0) {
            throw new ServiceException("亲，提交失败啦，已经存在辅助核算类别！");
        }
        accountingCategoryRepository.saveAndFlush(entity);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "accountingCategory", key = "#entity.accountSetsId"),
            @CacheEvict(value = "accountingCategoryEntity", key = "#entity.primaryKey")
    })
    public void update(AccountingCategory entity) {
        if (accountingCategoryRepository.count(qAccountingCategory.accountSetsId.eq(entity.getAccountSetsId())
                .and(qAccountingCategory.name.eq(entity.getName().trim()))
                .and(qAccountingCategory.id.ne(entity.getId()))) > 0) {
            throw new ServiceException("亲，提交失败啦，已经存在辅助核算类别！");
        }
        accountingCategoryRepository.save(entity);
    }

    public List<AccountingCategory> listByName(Long accountSetsId, List<String> nameList) {
        return bqf.selectFrom(qAccountingCategory).where(qAccountingCategory.accountSetsId.eq(accountSetsId)
                        .and(qAccountingCategory.name.in(nameList))).fetch()
                .stream().peek(ac -> bqf.detach(ac)).toList();
    }

    @Cacheable(value = "accountingCategory", key = "#accountSetsId")
    public List<AccountingCategoryDto> listByAccountSetsId(Long accountSetsId) {
        return bqf.selectFrom(qAccountingCategory)
                .where(qAccountingCategory.accountSetsId.eq(accountSetsId))
                .orderBy(qAccountingCategory.id.asc())
                .fetch().stream().peek(ac -> bqf.detach(ac)).map(accountingCategoryMapper::toDto).collect(Collectors.toList());
    }

    @Cacheable(value = "accountingCategoryEntity", key = "#primaryKey")
    public AccountingCategory getOne(PrimaryKey primaryKey) {
        return bqf.selectFrom(qAccountingCategory)
                .where(qAccountingCategory.accountSetsId.eq(primaryKey.getAccountSetsId())
                        .and(qAccountingCategory.id.eq(primaryKey.getId()))).fetchFirst();

    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "accountingCategory", key = "#primaryKey.accountSetsId"),
            @CacheEvict(value = "accountingCategoryEntity", key = "#primaryKey")
    })
    public void delete(PrimaryKey primaryKey) {
        Assert.isTrue(bqf.selectFrom(qAccountingCategoryDetails)
                              .where(qAccountingCategoryDetails.accountSetsId.eq(primaryKey.getAccountSetsId())
                                      .and(qAccountingCategoryDetails.categoryId.eq(primaryKey.getId())))
                              .fetchCount() == 0, "请先删除辅助数据");
        accountingCategoryRepository.deleteById(primaryKey);
    }

    @Transactional
    @Override
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        for (AccountingCategoryType type : AccountingCategoryType.values()) {
            if (!type.equals(AccountingCategoryType.CUSTOMIZATION)) {
                AccountingCategory category = new AccountingCategory();
                category.setId((long) type.ordinal());
                category.setAccountSetsId(accountSets.getId());
                category.setCanEdit(false);
                category.setName(type.display);
                category.setCategoryType(type);
                category.setColumns(type.columns);
                category.setSystemDefault(true);
                accountingCategoryRepository.save(category);
            }
        }
    }

    @Transactional
    @Override
    @Caching(evict = {
            @CacheEvict(value = "accountingCategory", key = "#accountSetsId"),
            @CacheEvict(value = "accountingCategoryEntity", allEntries = true)
    })
    public void clear(Long accountSetsId) {
        jqf.delete(qAccountingCategoryDetails)
                .where(qAccountingCategoryDetails.accountSetsId.eq(accountSetsId))
                .execute();

        jqf.delete(QAccountingCategory.accountingCategory)
                .where(QAccountingCategory.accountingCategory.accountSetsId.eq(accountSetsId))
                .execute();

        jqf.delete(QAuxiliaryDetail.auxiliaryDetail)
                .where(QAuxiliaryDetail.auxiliaryDetail.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(Dict.create()
                .set("categories", bqf.selectFrom(qAccountingCategory)
                        .where(qAccountingCategory.accountSetsId.eq(accountSetsId))
                        .fetch())
                .set("categoryDetails", bqf.selectFrom(QAccountingCategoryDetails.accountingCategoryDetails)
                        .where(QAccountingCategoryDetails.accountingCategoryDetails.accountSetsId.eq(accountSetsId))
                        .fetch())
                .set("auxiliaryDetails", bqf.selectFrom(QAuxiliaryDetail.auxiliaryDetail)
                        .where(QAuxiliaryDetail.auxiliaryDetail.accountSetsId.eq(accountSetsId))
                        .fetch()));
    }

    @Override
    public Class<?> backupType() {
        return AccountingCategory.class;
    }

    /**
     * 恢复备份
     *
     * @param accountSetsId
     * @param json
     * @return
     */
    @Override
    @Caching(evict = {
            @CacheEvict(value = "accountingCategory", key = "#accountSetsId"),
            @CacheEvict(value = "accountingCategoryEntity", allEntries = true)
    })
    public void recover(Long accountSetsId, String json) {
        JSONObject jsonData = JSON.parseObject(json, JSONObject.class);
        List<AccountingCategory> categories = jsonData.getJSONArray("categories").toJavaList(AccountingCategory.class);
        for (AccountingCategory category : categories) {
            category.setAccountSetsId(accountSetsId);
        }
        accountingCategoryRepository.saveAll(categories);

        List<AccountingCategoryDetails> categoryDetails = jsonData.getJSONArray("categoryDetails").toJavaList(AccountingCategoryDetails.class);
        for (AccountingCategoryDetails details : categoryDetails) {
            details.setAccountSetsId(accountSetsId);
        }
        accountingCategoryDetailsRepository.saveAll(categoryDetails);

        List<AuxiliaryDetail> auxiliaryDetails = jsonData.getJSONArray("auxiliaryDetails").toJavaList(AuxiliaryDetail.class);
        for (AuxiliaryDetail details : auxiliaryDetails) {
            details.setAccountSetsId(accountSetsId);
        }
        auxiliaryDetailRepository.saveAll(auxiliaryDetails);
    }


    /**
     * 根据ID列表获取所有辅助项
     *
     * @param accountSetsId
     * @param categoryIdSet
     * @return
     */
    public List<AccountingCategoryDto> listByIdsWidthData(Long accountSetsId, Set<Long> categoryIdSet) {
        return bqf.selectFrom(qAccountingCategory)
                .where(qAccountingCategory.accountSetsId.eq(accountSetsId)
                        .and(qAccountingCategory.id.in(categoryIdSet)))
                .orderBy(qAccountingCategory.id.asc())
                .fetch().stream().map(c -> {
                    AccountingCategoryDto dto = new AccountingCategoryDto();
                    dto.setId(c.getId());
                    dto.setCategoryType(c.getCategoryType());
                    dto.setName(c.getName());
                    AuxiliaryDataService dataService = SpringUtil.getBean(c.getCategoryType().dataService);
                    if (AccountingCategoryType.CUSTOMIZATION.equals(c.getCategoryType())) {
                        dto.setDetails(dataService.loadData(accountSetsId, c.getId()));
                    } else {
                        dto.setDetails(dataService.loadData(accountSetsId));
                    }
                    return dto;
                }).collect(Collectors.toList());
    }

    /**
     * 批量保存
     *
     * @param newAcDetails
     */
    @Transactional
    public void saveAll(List<Object> newAcDetails) {
        newAcDetails.forEach(o -> {
            AcDetails d = (AcDetails) o;
            AuxiliaryDataService dataService = SpringUtil.getBean(d.getCategoryType().dataService);
            dataService.save(d);
        });
    }
}


