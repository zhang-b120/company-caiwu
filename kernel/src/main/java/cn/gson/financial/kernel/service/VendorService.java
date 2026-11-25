package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.VendorRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (Vendor)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-04-05 14:05:02</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VendorService extends AbsAuxiliaryDataService<Vendor> {

    private final QVendor qVendor = QVendor.vendor;

    private final VendorRepository vendorRepository;

    @Override
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        List<Vendor> flows = bqf.selectFrom(qVendor)
                .where(qVendor.accountSetsId.eq(accountSetsTemplate.getId()))
                .fetch();

        if (CollUtil.isNotEmpty(flows)) {
            vendorRepository.saveAll(flows.stream().map(s -> {
                bqf.detach(s);
                Vendor bean = BeanUtil.toBean(s, Vendor.class);
                bean.setAccountSetsId(accountSets.getId());
                bean.setCategoryType(AccountingCategoryType.VENDOR);
                return bean;
            }).collect(Collectors.toList()));
        }
    }

    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qVendor)
                .where(qVendor.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qVendor)
                .where(qVendor.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<Vendor> backupType() {
        return Vendor.class;
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
            List<Vendor> javaList = JSONArray.parseArray(json).toJavaList(backupType());
            javaList.forEach(s -> s.setAccountSetsId(accountSetsId));
            vendorRepository.saveAll(javaList);
        }
    }

    @Override
    public JpaRepository<Vendor, PrimaryKey> getRepository() {
        return vendorRepository;
    }

    @Override
    public QAcDetails getQ() {
        return qVendor._super;
    }

    @Override
    public AccountingCategoryType getType() {
        return AccountingCategoryType.VENDOR;
    }
}

