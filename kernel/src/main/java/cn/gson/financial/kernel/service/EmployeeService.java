package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.EmployeeDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.mapper.EmployeeMapper;
import cn.gson.financial.kernel.model.repo.EmployeeRepository;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.blazebit.persistence.PagedList;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 *
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (Employee)</li>
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
public class EmployeeService extends AbsAuxiliaryDataService<Employee> {
    private final EmployeeMapper employeeMapper;

    private final QEmployee qEmployee = QEmployee.employee;

    private final QDepartment qDepartment = QDepartment.department;

    private final EmployeeRepository employeeRepository;

    public PageResults<EmployeeDto> list(Page page, String filter, Long accountSetsId) {
        BooleanExpression expression = qEmployee.accountSetsId.eq(accountSetsId);
        if (StrUtil.isNotEmpty(filter)) {
            expression = qEmployee.code.contains(filter).or(qEmployee.name.contains(filter));
        }
        PagedList<Tuple> pagedList = bqf.selectFrom(qEmployee)
                .select(qEmployee, qDepartment.name)
                .leftJoin(qDepartment).on(qDepartment.id.eq(qEmployee.departmentId).and(qDepartment.accountSetsId.eq(accountSetsId)))
                .where(expression)
                .orderBy(qEmployee.code.castToNum(Integer.class).asc(), qEmployee.id.desc(), qEmployee.accountSetsId.desc())
                .fetchPage(page.getOffset(), page.getOffsetEnd());
        List<EmployeeDto> projectDtoList = pagedList.stream().map(tuple -> {
            EmployeeDto dto = employeeMapper.toDto(tuple.get(qEmployee));
            dto.setDepartmentName(tuple.get(qDepartment.name));
            return dto;
        }).collect(Collectors.toList());
        return new PageResults<>(projectDtoList, page, pagedList.getTotalSize());
    }

    @Override
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        List<Employee> flows = bqf.selectFrom(qEmployee)
                .where(qEmployee.accountSetsId.eq(accountSetsTemplate.getId()))
                .fetch();

        if (CollUtil.isNotEmpty(flows)) {
            employeeRepository.saveAll(flows.stream().map(s -> {
                bqf.detach(s);
                Employee bean = BeanUtil.toBean(s, Employee.class);
                bean.setCategoryType(AccountingCategoryType.EMPLOYEE);
                bean.setAccountSetsId(accountSets.getId());
                return bean;
            }).collect(Collectors.toList()));
        }
    }

    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qEmployee)
                .where(qEmployee.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qEmployee)
                .where(qEmployee.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<Employee> backupType() {
        return Employee.class;
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
            List<Employee> javaList = JSONArray.parseArray(json).toJavaList(backupType());
            javaList.forEach(s -> s.setAccountSetsId(accountSetsId));
            employeeRepository.saveAll(javaList);
        }
    }

    @Override
    public JpaRepository<Employee, PrimaryKey> getRepository() {
        return employeeRepository;
    }

    @Override
    public QAcDetails getQ() {
        return qEmployee._super;
    }

    @Override
    public AccountingCategoryType getType() {
        return AccountingCategoryType.EMPLOYEE;
    }

    /**
     * 重写save方法，添加身份证号和姓名重复检查
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AcDetails save(AcDetails entity) {
        Employee employee = (Employee) entity;
        
        // 去除身份证号和姓名的前后空格
        if (StrUtil.isNotEmpty(employee.getIdCard())) {
            employee.setIdCard(employee.getIdCard().trim());
        }
        if (StrUtil.isNotEmpty(employee.getName())) {
            employee.setName(employee.getName().trim());
        }
        
        // 检查身份证号和姓名是否重复
        if (StrUtil.isNotEmpty(employee.getIdCard()) && StrUtil.isNotEmpty(employee.getName())) {
            Long excludeId = employee.getPrimaryKey() != null ? employee.getPrimaryKey().getId() : null;
            Employee existing = findByIdCardAndName(employee.getAccountSetsId(), employee.getIdCard(), employee.getName(), excludeId);
            if (existing != null) {
                throw new ServiceException("身份证号 [" + employee.getIdCard() + "] 和姓名 [" + employee.getName() + "] 的员工已存在，请检查~");
            }
        }
        
        // 调用父类的save方法
        return super.save(entity);
    }

    public String getEmployeeCode(Long accountSetsId){
        // 取该账套全部员工编号，按"数值"求最大值再 +1。
        // 不能用 SQL 的 MAX(code)（字符串比较："8"/"9" 会被判为大于 "10"/"100"），
        // 历史编号又混着补零("001")和不补零("7")，故统一 parseInt 归一为数值后取最大，
        // 避免位数不一致时回退重号、与已存在（含已离职未删除）员工编码撞 "已重复"。
        List<String> codes = bqf.selectFrom(qEmployee)
                .select(qEmployee.code)
                .where(qEmployee.accountSetsId.eq(accountSetsId))
                .fetch();
        int max = 0;
        for (String c : codes) {
            if (StringUtils.isBlank(c)) {
                continue;
            }
            try {
                max = Math.max(max, Integer.parseInt(c.trim()));
            } catch (NumberFormatException ignore) {
                // 非纯数字的历史脏编号跳过，不参与序号计算
            }
        }
        // 统一补零到 3 位（与既有 001~006 风格一致）；超过 999 自然进位为 4 位
        return String.format("%03d", max + 1);
    }

    /**
     * 根据身份证号和姓名查询员工
     *
     * @param accountSetsId 账套ID
     * @param idCard 身份证号
     * @param name 姓名
     * @param excludeId 排除的员工ID（更新时使用）
     * @return 员工对象，如果不存在返回null
     */
    public Employee findByIdCardAndName(Long accountSetsId, String idCard, String name, Long excludeId) {
        if (StrUtil.isEmpty(idCard) || StrUtil.isEmpty(name)) {
            return null;
        }
        // 去除身份证号和姓名的前后空格
        String trimmedIdCard = idCard.trim();
        String trimmedName = name.trim();
        BooleanExpression expression = qEmployee.accountSetsId.eq(accountSetsId)
                .and(qEmployee.idCard.eq(trimmedIdCard))
                .and(qEmployee.name.eq(trimmedName));
        if (excludeId != null) {
            expression = expression.and(qEmployee.id.ne(excludeId));
        }
        return bqf.selectFrom(qEmployee)
                .where(expression)
                .fetchFirst();
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteEmployee(List<Long> records) {
        jqf.delete(qEmployee).where(qEmployee.id.in(records)).execute();
    }
}

