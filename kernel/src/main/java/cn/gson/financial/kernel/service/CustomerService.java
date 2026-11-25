package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.CustomerRepository;
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
 * <li>Description : (Customer)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-04-05 14:04:18</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerService extends AbsAuxiliaryDataService<Customer> {

    private final QCustomer qCustomer = QCustomer.customer;

    private final CustomerRepository customerRepository;

    @Override
    @Transactional
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        List<Customer> flows = bqf.selectFrom(qCustomer)
                .where(qCustomer.accountSetsId.eq(accountSetsTemplate.getId()))
                .fetch();

        if (CollUtil.isNotEmpty(flows)) {
            customerRepository.saveAll(flows.stream().map(s -> {
                bqf.detach(s);
                Customer bean = BeanUtil.toBean(s, Customer.class);
                bean.setCategoryType(AccountingCategoryType.CUSTOMER);
                bean.setAccountSetsId(accountSets.getId());
                return bean;
            }).collect(Collectors.toList()));
        }
    }

    @Override
    @Transactional
    public void clear(Long accountSetsId) {
        jqf.delete(qCustomer)
                .where(qCustomer.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qCustomer)
                .where(qCustomer.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<Customer> backupType() {
        return Customer.class;
    }

    /**
     * 恢复备份
     *
     * @param accountSetsId
     * @param json
     * @return
     */
    @Override
    @Transactional
    public void recover(Long accountSetsId, String json) {
        if (StrUtil.isNotEmpty(json)) {
            List<Customer> javaList = JSONArray.parseArray(json).toJavaList(backupType());
            javaList.forEach(s -> s.setAccountSetsId(accountSetsId));
            customerRepository.saveAll(javaList);
        }
    }

    @Override
    public JpaRepository<Customer, PrimaryKey> getRepository() {
        return customerRepository;
    }

    @Override
    public QAcDetails getQ() {
        return qCustomer._super;
    }

    @Override
    public AccountingCategoryType getType() {
        return AccountingCategoryType.CUSTOMER;
    }
}

