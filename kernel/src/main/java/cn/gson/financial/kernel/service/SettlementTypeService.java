package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.AccountSetsTemplate;
import cn.gson.financial.kernel.model.entity.QSettlementType;
import cn.gson.financial.kernel.model.entity.SettlementType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.SettlementTypeRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
public class SettlementTypeService extends AbsService implements AccountSetsProcessor, BackupProcessor {

    private final SettlementTypeRepository settlementTypeRepository;

    private final QSettlementType qSettlementType = QSettlementType.settlementType;


    public List<SettlementType> list(Long accountSetsId) {
        return bqf.selectFrom(qSettlementType).where(qSettlementType.accountSetsId.eq(accountSetsId)).fetch();
    }

    @Transactional
    public SettlementType update(PrimaryKey settlementTypeId, SettlementType settlementType) {
        SettlementType original = settlementTypeRepository.getReferenceById(settlementTypeId);
        BeanUtil.copyProperties(settlementType, original, CopyOptions.create().ignoreNullValue());
        settlementType = settlementTypeRepository.save(original);
        return settlementType;
    }

    @Transactional
    public SettlementType save(SettlementType settlementType) {
        if (settlementType.getId() != null) {
            SettlementType original = settlementTypeRepository.getReferenceById(settlementType.getPrimaryKey());
            BeanUtil.copyProperties(settlementType, original, CopyOptions.create().ignoreNullValue());
            settlementType = settlementTypeRepository.save(original);
        } else {
            settlementType = settlementTypeRepository.save(settlementType);
        }
        return settlementType;
    }

    @Transactional
    public void delete(PrimaryKey primaryKey) {
        settlementTypeRepository.deleteById(primaryKey);
    }

    @Transactional
    @Override
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        //账套默认值
        settlementTypeRepository.saveAll(bqf.selectFrom(qSettlementType)
                .where(qSettlementType.accountSetsId.eq(accountSetsTemplate.getId()))
                .fetch().stream().map(s -> {
                    bqf.detach(s);
                    SettlementType bean = BeanUtil.toBean(s, SettlementType.class);
                    bean.setAccountSetsId(accountSets.getId());
                    return bean;
                }).collect(Collectors.toList()));
    }

    @Transactional
    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(QSettlementType.settlementType)
                .where(QSettlementType.settlementType.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qSettlementType)
                .where(qSettlementType.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<?> backupType() {
        return SettlementType.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {
        List<SettlementType> entityList = JSON.parseArray(json, SettlementType.class);
        for (SettlementType entity : entityList) {
            entity.setAccountSetsId(accountSetsId);
            settlementTypeRepository.save(entity);
        }
    }
}


