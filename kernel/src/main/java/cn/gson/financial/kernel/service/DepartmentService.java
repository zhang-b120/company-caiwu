package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.DepartmentRepository;
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
 * <li>Description : (Department)</li>
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
public class DepartmentService extends AbsAuxiliaryDataService<Department> {

    private final QDepartment qDepartment = QDepartment.department;

    private final DepartmentRepository departmentRepository;

    @Override
    @Transactional
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        List<Department> flows = bqf.selectFrom(qDepartment)
                .where(qDepartment.accountSetsId.eq(accountSetsTemplate.getId()))
                .fetch();

        if (CollUtil.isNotEmpty(flows)) {
            departmentRepository.saveAll(flows.stream().map(s -> {
                bqf.detach(s);
                Department bean = BeanUtil.toBean(s, Department.class);
                bean.setCategoryId((long) AccountingCategoryType.DEPARTMENT.ordinal());
                bean.setCategoryType(AccountingCategoryType.DEPARTMENT);
                bean.setAccountSetsId(accountSets.getId());
                return bean;
            }).collect(Collectors.toList()));
        }
    }

    @Override
    @Transactional
    public void clear(Long accountSetsId) {
        jqf.delete(qDepartment)
                .where(qDepartment.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qDepartment)
                .where(qDepartment.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<Department> backupType() {
        return Department.class;
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
            List<Department> javaList = JSONArray.parseArray(json).toJavaList(backupType());
            javaList.forEach(s -> s.setAccountSetsId(accountSetsId));
            departmentRepository.saveAll(javaList);
        }
    }

    @Override
    public JpaRepository<Department, PrimaryKey> getRepository() {
        return departmentRepository;
    }

    @Override
    public QAcDetails getQ() {
        return qDepartment._super;
    }

    @Override
    public AccountingCategoryType getType() {
        return AccountingCategoryType.DEPARTMENT;
    }
}

