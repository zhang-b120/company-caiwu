package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.OrganizationRepository;
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
 * <li>Description : (Organization)</li>
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
public class OrganizationService extends AbsAuxiliaryDataService<Organization> {

    private final QOrganization qOrganization = QOrganization.organization;

    private final OrganizationRepository organizationRepository;

    @Override
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        List<Organization> flows = bqf.selectFrom(qOrganization)
                .where(qOrganization.accountSetsId.eq(accountSetsTemplate.getId()))
                .fetch();

        if (CollUtil.isNotEmpty(flows)) {
            organizationRepository.saveAll(flows.stream().map(s -> {
                bqf.detach(s);
                Organization bean = BeanUtil.toBean(s, Organization.class);
                bean.setAccountSetsId(accountSets.getId());
                bean.setCategoryType(AccountingCategoryType.ORGANIZATION);
                return bean;
            }).collect(Collectors.toList()));
        }
    }

    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qOrganization)
                .where(qOrganization.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qOrganization)
                .where(qOrganization.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<Organization> backupType() {
        return Organization.class;
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
            List<Organization> javaList = JSONArray.parseArray(json).toJavaList(backupType());
            javaList.forEach(s -> s.setAccountSetsId(accountSetsId));
            organizationRepository.saveAll(javaList);
        }
    }

    @Override
    public JpaRepository<Organization, PrimaryKey> getRepository() {
        return organizationRepository;
    }

    @Override
    public QAcDetails getQ() {
        return qOrganization._super;
    }

    @Override
    public AccountingCategoryType getType() {
        return AccountingCategoryType.ORGANIZATION;
    }
}

