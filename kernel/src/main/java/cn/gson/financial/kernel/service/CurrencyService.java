package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.aspect.CacheRemove;
import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.model.dto.CurrencyDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.AccountSetsTemplate;
import cn.gson.financial.kernel.model.entity.Currency;
import cn.gson.financial.kernel.model.entity.QCurrency;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.mapper.CurrencyMapper;
import cn.gson.financial.kernel.model.repo.CurrencyRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
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
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CurrencyService extends AbsService implements AccountSetsProcessor, BackupProcessor {

    private final CurrencyRepository currencyRepository;

    private final QCurrency qCurrency = QCurrency.currency;

    private final CurrencyMapper currencyMapper;

    @Transactional
    @CacheRemove(value = "currency", key = "#entity.accountSetsId")
    public Currency save(Currency entity) {
        if (entity.getPrimaryKey() == null) {
            Assert.isTrue(currencyRepository.count(qCurrency.accountSetsId.eq(entity.getAccountSetsId())
                    .and(qCurrency.code.eq(entity.getCode()))) == 0, "币别编码已经存在！");
            return currencyRepository.save(entity);
        } else {
            Assert.isTrue(currencyRepository.count(qCurrency.accountSetsId.eq(entity.getAccountSetsId())
                    .and(qCurrency.code.eq(entity.getCode())).and(qCurrency.id.ne(entity.getId()))) == 0, "币别编码已经存在！");
            Currency reference = currencyRepository.getReferenceById(entity.getPrimaryKey());
            //本币不允许更新
            Assert.isFalse(reference.getLocalCurrency(), "本币不允许更新~");

            BeanUtil.copyProperties(entity, reference, CopyOptions.create().ignoreNullValue());
            return currencyRepository.save(reference);
        }
    }

    /**
     * 币别列表
     *
     * @param accountSetsId
     * @return
     */
    @Cacheable(value = "currency", key = "#accountSetsId")
    public List<CurrencyDto> list(Long accountSetsId) {
        return bqf.selectFrom(qCurrency)
                .where(qCurrency.accountSetsId.eq(accountSetsId))
                .orderBy(qCurrency.localCurrency.desc(), qCurrency.id.asc())
                .fetch().stream().map(currencyMapper::toDto).collect(Collectors.toList());
    }

    /**
     * 根据给定币id查询返回列表和本位币
     *
     * @param accountSetsId 账套ID
     * @param currencyIdSet 币id列表
     * @return 列表和本位币集合
     */
    public List<CurrencyDto> fetchByIdArrWithLocal(Long accountSetsId, Set<Long> currencyIdSet) {
        return bqf.selectFrom(qCurrency)
                .where(qCurrency.accountSetsId.eq(accountSetsId)
                        .and(qCurrency.id.in(currencyIdSet).or(qCurrency.localCurrency.isTrue())))
                .orderBy(qCurrency.localCurrency.desc(), qCurrency.id.asc())
                .fetch().stream().map(currencyMapper::toDto).collect(Collectors.toList());
    }

    /**
     * 返回本位币
     *
     * @param accountSetsId 账套ID
     * @return 列表和本位币集合
     */
    @Cacheable(value = "currency", key = "#accountSetsId+':Local'")
    public CurrencyDto fetchLocal(Long accountSetsId) {
        Currency currency = bqf.selectFrom(qCurrency)
                .where(qCurrency.accountSetsId.eq(accountSetsId)
                        .and(qCurrency.localCurrency.isTrue()))
                .fetchFirst();
        Assert.notNull(currency, "账套未设置本币~");
        return currencyMapper.toDto(currency);
    }

    @Transactional
    @CacheRemove(value = "currency", key = "#primaryKey.accountSetsId")
    public void delete(PrimaryKey primaryKey) {
        Currency currency = currencyRepository.getReferenceById(primaryKey);

        Assert.isFalse(currency.getLocalCurrency(), "本位币不能删除！");

        currencyRepository.delete(currency);
    }

    public List<Currency> listByName(Long accountSetsId, List<String> names) {
        return bqf.selectFrom(qCurrency)
                .where(qCurrency.accountSetsId.eq(accountSetsId).and(qCurrency.name.in(names)))
                .orderBy(qCurrency.id.desc())
                .fetch();
    }

    @Transactional
    @Override
    @CacheRemove(value = "currency", key = "#accountSets.id")
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        List<Currency> currencyList = bqf.selectFrom(qCurrency)
                .where(qCurrency.accountSetsId.eq(accountSetsTemplate.getId()))
                .fetch();
        if (CollUtil.isEmpty(currencyList)) {
            Currency currency = new Currency();
            currency.setCode("RMB");
            currency.setName("人民币");
            currency.setExchangeRate(BigDecimal.ONE);
            currency.setLocalCurrency(true);
            currency.setAccountSetsId(accountSets.getId());
            currencyRepository.save(currency);
        } else {
            Assert.isTrue(currencyList.stream().anyMatch(Currency::getLocalCurrency), "币别未设置默认本币,请联系管理员设置~");

            currencyRepository.saveAll(currencyList.stream().map(s -> {
                bqf.detach(s);
                Currency bean = BeanUtil.toBean(s, Currency.class);
                bean.setAccountSetsId(accountSets.getId());
                return bean;
            }).collect(Collectors.toList()));
        }
    }

    @Transactional
    @Override
    @CacheRemove(value = "currency", key = "#accountSetsId")
    public void clear(Long accountSetsId) {
        jqf.delete(QCurrency.currency)
                .where(QCurrency.currency.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qCurrency)
                .where(qCurrency.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<?> backupType() {
        return Currency.class;
    }

    @Override
    @CacheRemove(value = "currency", key = "#accountSetsId")
    public void recover(Long accountSetsId, String json) {
        List<Currency> entityList = JSON.parseArray(json, Currency.class);
        for (Currency entity : entityList) {
            entity.setAccountSetsId(accountSetsId);
            currencyRepository.save(entity);
        }
    }

    @Transactional
    @CacheRemove(value = "currency", key = "#currency.accountSetsId")
    public void updateLocalCurrency(Currency currency) {
        jqf.update(qCurrency)
                .set(qCurrency.localCurrency, false)
                .where(qCurrency.accountSetsId.eq(currency.getAccountSetsId())
                        .and(qCurrency.id.ne(currency.getId())))
                .execute();
    }
}


