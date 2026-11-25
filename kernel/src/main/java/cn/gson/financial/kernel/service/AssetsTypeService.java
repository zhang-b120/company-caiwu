package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.dto.AssetsTypeDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.AssetsTypeRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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
public class AssetsTypeService extends AbsService implements AccountSetsProcessor, BackupProcessor {

    private final AssetsTypeRepository assetsTypeRepository;

    private final QAssetsType qAssetsType = QAssetsType.assetsType;

    private final QSubject qSubject = QSubject.subject;
    private final QSubject qSubject2 = new QSubject("sb2");

    private final QAssets qAssets = QAssets.assets;


    public List<AssetsTypeDto> list(Long accountSetsId) {
        List<AssetsTypeDto> datas = new ArrayList<>();
        bqf.selectFrom(qAssetsType)
                .select(qAssetsType, qSubject.fullName, qSubject2.fullName)
                .leftJoin(qSubject).on(qSubject.id.eq(qAssetsType.costAccount).and(qSubject.accountSetsId.eq(accountSetsId)))
                .leftJoin(qSubject2).on(qSubject2.id.eq(qAssetsType.depreciationAccount).and(qSubject2.accountSetsId.eq(accountSetsId)))
                .where(qAssetsType.accountSetsId.eq(accountSetsId)).fetch().forEach(row -> {
                    AssetsTypeDto dto = BeanUtil.toBean(row.get(qAssetsType), AssetsTypeDto.class);
                    dto.setCostName(row.get(qSubject.fullName));
                    dto.setDepreciationName(row.get(qSubject2.fullName));
                    datas.add(dto);
                });
        return datas;
    }

    @Transactional
    public AssetsType update(PrimaryKey primaryKey, AssetsType assetsType) {
        AssetsType original = assetsTypeRepository.getReferenceById(primaryKey);
        BeanUtil.copyProperties(assetsType, original, CopyOptions.create().ignoreNullValue());
        assetsType = assetsTypeRepository.save(original);
        return assetsType;
    }

    @Transactional
    public AssetsType save(AssetsType assetsType) {
        if (assetsType.getId() != null) {
            AssetsType original = assetsTypeRepository.getReferenceById(assetsType.getPrimaryKey());
            BeanUtil.copyProperties(assetsType, original, CopyOptions.create().ignoreNullValue());
            assetsType = assetsTypeRepository.save(original);
        } else {
            assetsType = assetsTypeRepository.save(assetsType);
        }
        return assetsType;
    }

    @Transactional
    public void delete(Long accountSetsId, Long assetsTypeId) {
        if (bqf.selectFrom(qAssets).where(qAssets.assetsTypeId.eq(assetsTypeId)
                .and(qAssets.accountSetsId.eq(accountSetsId))).fetchCount() > 0) {
            throw new ServiceException("已被使用，不能删除！");
        }
        jqf.delete(qAssetsType)
                .where(qAssetsType.accountSetsId.eq(accountSetsId).and(qAssetsType.id.eq(assetsTypeId)))
                .execute();
    }

    /**
     * 账套初始化
     *
     * @param accountSets
     * @param accountSetsTemplate
     */
    @Transactional
    @Override
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        assetsTypeRepository.saveAll(bqf.selectFrom(qAssetsType)
                .where(qAssetsType.accountSetsId.eq(accountSetsTemplate.getId()))
                .fetch().stream().map(s -> {
                    bqf.detach(s);
                    AssetsType bean = BeanUtil.toBean(s, AssetsType.class);
                    bean.setAccountSetsId(accountSets.getId());
                    return bean;
                }).collect(Collectors.toList()));
    }

    /**
     * 清除账套下的数据
     *
     * @param accountSetsId
     */
    @Transactional
    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qAssetsType)
                .where(qAssetsType.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qAssetsType)
                .where(qAssetsType.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<?> backupType() {
        return AssetsType.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {
        List<AssetsType> entityList = JSON.parseArray(json, AssetsType.class);
        for (AssetsType entity : entityList) {
            entity.setAccountSetsId(accountSetsId);
            assetsTypeRepository.save(entity);
        }
    }
}


