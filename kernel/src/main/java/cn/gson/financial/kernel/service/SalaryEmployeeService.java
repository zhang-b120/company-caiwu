package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.common.PinYinUtil;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.SalaryEmployeeDto;
import cn.gson.financial.kernel.model.dto.SalarySocialEmployeeDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.mapper.SalaryEmployeeMapper;
import cn.gson.financial.kernel.model.repo.EmployeeRepository;
import cn.gson.financial.kernel.model.repo.SalarySocialEmployeeRepository;
import cn.gson.financial.kernel.model.bean.FieldValue;
import cn.gson.financial.kernel.model.bean.Value;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.ua.resp.GetEmployeeSalarySumInfoResp;
import cn.gson.financial.kernel.model.vo.*;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.blazebit.persistence.PagedList;
import com.blazebit.persistence.querydsl.BlazeJPAQuery;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringExpression;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-05 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-05</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SalaryEmployeeService extends AbsService {

    private final SalaryEmployeeMapper salaryEmployeeMapper;

    private final EmployeeRepository employeeRepository;

    private final SalarySocialEmployeeRepository salarySocialEmployeeRepository;

    private final QEmployee qEmployee = QEmployee.employee;

    private final QDepartment qDepartment = QDepartment.department;
    private final QSubject qSubject = QSubject.subject;
    private final QSalarySocial qSalarySocial = QSalarySocial.salarySocial;
    private final QSalarySocialEmployee qSalarySocialEmployee = QSalarySocialEmployee.salarySocialEmployee;

    private final QSalary qSalary = QSalary.salary;

    private final QSalaryFieldValue qSalaryFieldValue = QSalaryFieldValue.salaryFieldValue;
    
    private final QSalaryField qSalaryField = QSalaryField.salaryField;
    /**
     * 分页员工列表
     *
     * @param accountSetId
     * @param page
     * @param queryVo
     * @return
     */
    public PageResults<SalaryEmployeeDto> employeeList(Long accountSetId, Page page, QueryVo queryVo) {
        StringExpression expression = Expressions.asString("个人：").concat(qSalarySocialEmployee.personNum.sum().stringValue())
                .concat("，公司：").concat(qSalarySocialEmployee.companyNum.sum().stringValue());

        BlazeJPAQuery<String> social = bqf.selectFrom(qSalarySocialEmployee)
                .select(expression)
                .where(qSalarySocialEmployee.empId.eq(qEmployee.id).and(qEmployee.accountSetsId.eq(accountSetId)));

        // 默认社保信息
        String defaultSocial = bqf.selectFrom(qSalarySocial)
                .select(Expressions.asString("个人：").concat(qSalarySocial.personNum.sum().stringValue())
                        .concat("，公司：").concat(qSalarySocial.companyNum.sum().stringValue()))
                .where(qSalarySocial.accountSetsId.eq(accountSetId))
                .fetchFirst();

        PagedList<Tuple> pageData = bqf.selectFrom(qEmployee)
                .select(qEmployee, qSubject.name, qSubject.code, qDepartment.name, social)
                .leftJoin(qSubject).on(qEmployee.provisionId.eq(qSubject.id).and(qSubject.accountSetsId.eq(accountSetId)))
                .leftJoin(qDepartment).on(qEmployee.departmentId.eq(qDepartment.id).and(qDepartment.accountSetsId.eq(accountSetId)))
                .where(qEmployee.accountSetsId.eq(accountSetId).and(queryVo.query()))
                .orderBy(qEmployee.code.castToNum(Integer.class).asc(), qEmployee.id.asc())
                .fetchPage(page.getOffset(), page.getOffsetEnd());

        List<SalaryEmployeeDto> employeeDtos = pageData.stream().map(tuple -> {
            SalaryEmployeeDto employeeDto = salaryEmployeeMapper.toDto(tuple.get(qEmployee));
            employeeDto.setProvisionName(tuple.get(qSubject.name));
            employeeDto.setProvisionCode(tuple.get(qSubject.code));
            employeeDto.setDepartmentName(tuple.get(qDepartment.name));
            employeeDto.setSocial(StrUtil.emptyToDefault(tuple.get(social), defaultSocial));
            return employeeDto;
        }).toList();

        return new PageResults<>(employeeDtos, page, pageData.getTotalSize());
    }

    /**
     * 批量更新计提科目
     *
     * @param accountSetId
     * @param provisionVo
     */
    @Transactional
    public void updateProvision(Long accountSetId, BatchProvisionVo provisionVo) {
        List<Employee> details = bqf.selectFrom(qEmployee)
                .where(qEmployee.accountSetsId.eq(accountSetId)
                        .and(qEmployee.id.in(provisionVo.getRecords())))
                .fetch().stream().peek(ed -> ed.setProvisionId(provisionVo.getProvisionId()))
                .toList();
        employeeRepository.saveAll(details);
    }

    /**
     * 批量设置社保信息
     *
     * @param accountSetId
     * @param socialVo
     */
    @Transactional
    public void updateSocial(Long accountSetId, BatchSocialVo socialVo) {
        jqf.delete(qSalarySocialEmployee)
                .where(qSalarySocialEmployee.accountSetsId.eq(accountSetId)
                        .and(qSalarySocialEmployee.empId.in(socialVo.getRecords())))
                .execute();
        //构造员工社保配置
        List<SalarySocialEmployee> socialEmployees = new ArrayList<>();
        for (Long recordId : socialVo.getRecords()) {
            socialEmployees.addAll(socialVo.getSocials().stream().map(social -> {
                SalarySocialEmployee socialEmployee = BeanUtil.toBean(social, SalarySocialEmployee.class, CopyOptions.create().setIgnoreProperties("id"));
                socialEmployee.setAccountSetsId(accountSetId);
                socialEmployee.setEmpId(recordId);
                socialEmployee.setSocialId(Optional.ofNullable(social.getSocialId()).orElse(social.getId()));
                socialEmployee.setCompanyNum(NumberUtil.mul(social.getDepositBase(), NumberUtil.div(social.getCompanyRatio(), 100)));
                socialEmployee.setPersonNum(NumberUtil.mul(social.getDepositBase(), NumberUtil.div(social.getPersonRatio(), 100)));
                return socialEmployee;
            }).toList());
        }
        salarySocialEmployeeRepository.saveAll(socialEmployees);

        // 更新是否购买社保字段
        if (socialVo.getPurchase() != null) {
            jqf.update(qEmployee)
                    .set(qEmployee.purchase, socialVo.getPurchase())
                    .where(qEmployee.accountSetsId.eq(accountSetId)
                            .and(qEmployee.id.in(socialVo.getRecords())))
                    .execute();
        }
    }

    /**
     * 加载员工社保信息
     *
     * @param accountSetId
     * @param empId
     * @return
     */
    public List<SalarySocialEmployeeDto> loadSocial(Long accountSetId, Long empId) {
        List<SalarySocialEmployeeDto> socialEmployees = bqf.selectFrom(qSalarySocialEmployee)
                .select(qSalarySocialEmployee, qSalarySocial.name)
                .innerJoin(qSalarySocial).on(qSalarySocial.id.eq(qSalarySocialEmployee.socialId).and(qSalarySocialEmployee.accountSetsId.eq(qSalarySocial.accountSetsId)))
                .where(qSalarySocialEmployee.empId.eq(empId).and(qSalarySocialEmployee.accountSetsId.eq(accountSetId)))
                .fetch().stream().map(tuple -> {
                    SalarySocialEmployeeDto bean = BeanUtil.toBean(tuple.get(qSalarySocialEmployee), SalarySocialEmployeeDto.class);
                    bean.setName(tuple.get(qSalarySocial.name));
                    return bean;
                }).toList();
        //取默认值
        if (CollUtil.isEmpty(socialEmployees)) {
            socialEmployees = bqf.selectFrom(qSalarySocial)
                    .where(qSalarySocial.accountSetsId.eq(accountSetId))
                    .fetch().stream().map(salarySocial -> {
                        SalarySocialEmployeeDto bean = BeanUtil.toBean(salarySocial, SalarySocialEmployeeDto.class);
                        bean.setId(null);
                        bean.setEmpId(empId);
                        bean.setSocialId(salarySocial.getId());
                        return bean;
                    }).toList();
        }
        return socialEmployees;
    }

    /**
     * 加载员工社保信息
     *
     * @param accountSetId
     * @param empIds
     * @return
     */
    public EmployeeSocialVo loadSocial(Long accountSetId, List<Long> empIds) {
        //已经自定义社保的员工社保信息
        Map<Long, List<SalarySocialEmployeeDto>> empMap = bqf.selectFrom(qSalarySocialEmployee)
                .select(qSalarySocialEmployee, qSalarySocial.name)
                .innerJoin(qSalarySocial).on(qSalarySocial.id.eq(qSalarySocialEmployee.socialId).and(qSalarySocialEmployee.accountSetsId.eq(qSalarySocial.accountSetsId)))
                .where(qSalarySocialEmployee.empId.in(empIds)
                        .and(qSalarySocialEmployee.accountSetsId.eq(accountSetId)))
                .fetch().stream().map(tuple -> {
                    SalarySocialEmployeeDto bean = BeanUtil.toBean(tuple.get(qSalarySocialEmployee), SalarySocialEmployeeDto.class);
                    bean.setName(tuple.get(qSalarySocial.name));
                    return bean;
                }).collect(Collectors.groupingBy(SalarySocialEmployeeDto::getEmpId));
        //默认社保字典
        Map<Long, String> socialNameMap = new HashMap<>();
        //默认员工社保信息
        List<SalarySocialEmployeeDto> socialEmployees = bqf.selectFrom(qSalarySocial)
                .where(qSalarySocial.accountSetsId.eq(accountSetId))
                .fetch().stream().map(salarySocial -> {
                    SalarySocialEmployeeDto bean = BeanUtil.toBean(salarySocial, SalarySocialEmployeeDto.class);
                    bean.setId(null);
                    bean.setSocialId(salarySocial.getId());
                    socialNameMap.put(salarySocial.getId(), salarySocial.getName());
                    return bean;
                }).toList();

        Map<Long, Map<String, BigDecimal>> newMap = new HashMap<>();

        for (Long empId : empIds) {
            //没有自定义的就取默认社保信息
            List<SalarySocialEmployeeDto> socialEmployeeList = empMap.getOrDefault(empId, socialEmployees);
            //行转换成列
            Map<String, BigDecimal> valMap = new HashMap<>();
            for (SalarySocialEmployeeDto socialEmployee : socialEmployeeList) {
                valMap.put("个人" + socialEmployee.getName(), socialEmployee.getPersonNum());
                valMap.put("企业" + socialEmployee.getName(), socialEmployee.getCompanyNum());
            }
            newMap.put(empId, valMap);
        }

        return new EmployeeSocialVo(newMap, socialNameMap);
    }

    /**
     * 检查员工社保项目是否在工资记录中使用
     *
     * @param accountSetId
     * @param empId
     * @param socialEmployeeId
     * @return true 如果已使用，false 如果未使用
     */
    public boolean isSocialEmployeeUsedInSalary(Long accountSetId, Long empId, Long socialEmployeeId) {
        // 获取员工社保项目信息
        SalarySocialEmployee socialEmployee = salarySocialEmployeeRepository.findById(
                new cn.gson.financial.kernel.model.hibernate.PrimaryKey(socialEmployeeId, accountSetId))
                .orElse(null);
        
        if (socialEmployee == null || socialEmployee.getSocialId() == null) {
            return false;
        }
        
        Long socialId = socialEmployee.getSocialId();
        
        // 找到所有关联到该社保项目的工资字段
        List<SalaryField> salaryFields = bqf.selectFrom(qSalaryField)
                .where(qSalaryField.accountSetsId.eq(accountSetId)
                        .and(qSalaryField.socialFieldId.eq(socialId)))
                .fetch();
        
        if (CollUtil.isEmpty(salaryFields)) {
            return false;
        }
        
        // 获取这些工资字段的ID列表
        List<Long> fieldIds = salaryFields.stream()
                .map(SalaryField::getId)
                .toList();
        
        // 查询该员工的所有工资记录
        List<SalaryFieldValue> fieldValues = bqf.selectFrom(qSalaryFieldValue)
                .where(qSalaryFieldValue.accountSetsId.eq(accountSetId)
                        .and(qSalaryFieldValue.empId.eq(empId)))
                .fetch();
        
        // 检查这些工资字段在工资记录中是否有非零值
        for (SalaryFieldValue fieldValue : fieldValues) {
            FieldValue fv = fieldValue.getFieldValue();
            if (fv == null) {
                continue;
            }
            
            for (Long fieldId : fieldIds) {
                Value value = fv.get(fieldId.toString());
                if (value != null && value.getValue() != null) {
                    // 检查值是否非零
                    try {
                        BigDecimal val = NumberUtil.toBigDecimal(value.getValue().toString());
                        if (NumberUtil.isGreater(val, BigDecimal.ZERO)) {
                            return true;
                        }
                    } catch (Exception e) {
                        // 如果转换失败，忽略
                    }
                }
            }
        }
        
        return false;
    }

    /**
     * 删除单个员工社保项目
     *
     * @param accountSetId
     * @param empId
     * @param socialEmployeeId
     */
    @Transactional
    public void deleteSocialEmployee(Long accountSetId, Long empId, Long socialEmployeeId) {
        // 检查是否已有工资记录使用
        if (isSocialEmployeeUsedInSalary(accountSetId, empId, socialEmployeeId)) {
            throw new ServiceException("该社保项目已在工资记录中使用，无法删除");
        }
        
        // 执行删除
        jqf.delete(qSalarySocialEmployee)
                .where(qSalarySocialEmployee.accountSetsId.eq(accountSetId)
                        .and(qSalarySocialEmployee.empId.eq(empId))
                        .and(qSalarySocialEmployee.id.eq(socialEmployeeId)))
                .execute();
    }

    /**
     * 批量更新员工状态
     *
     * @param accountSetId
     * @param statusVo
     */
    @Transactional
    public void updateStatus(Long accountSetId, BatchStatusVo statusVo) {
        switch (statusVo.getStatus()) {
            case "离职" -> {
                Assert.notNull(statusVo.getDimissionDate(), "请填写离职时间");
                jqf.update(qEmployee)
                        .set(qEmployee.status, statusVo.getStatus())
                        .set(qEmployee.dimissionDate, statusVo.getDimissionDate())
                        .where(qEmployee.accountSetsId.eq(accountSetId)
                                .and(qEmployee.id.in(statusVo.getRecords())))
                        .execute();
            }
            case "在职" -> jqf.update(qEmployee)
                    .set(qEmployee.status, statusVo.getStatus())
                    .setNull(qEmployee.dimissionDate)
                    .where(qEmployee.accountSetsId.eq(accountSetId)
                            .and(qEmployee.id.in(statusVo.getRecords())))
                    .execute();
        }
    }

    /**
     * 员工导入
     *
     * @param importData
     */
    @Transactional
    public void importData(Map<String, SalaryEmpImportVo> importData) {
        List<SalarySocialEmployee> socialEmployees = new ArrayList<>();
        List<Employee> employees = new ArrayList<>();
        for (SalaryEmpImportVo value : importData.values()) {
            Employee employee = value.getEmployee();
            employee.setId(snowflakeId.generate());
            employee.setMnemonics(PinYinUtil.getFirstLettersLo(employee.getName()));
            employees.add(employee);
            if (CollUtil.isNotEmpty(value.getSocialEmployees())) {
                employee.setPurchase(true);
                socialEmployees.addAll(value.getSocialEmployees().stream().peek(s -> s.setEmpId(employee.getId())).toList());
            }
        }

        employeeRepository.saveAllAndFlush(employees);
        salarySocialEmployeeRepository.saveAllAndFlush(socialEmployees);
    }

    /**
     * 查询不在列表的员工
     *
     * @param accountSetId
     * @param idList
     * @return
     */
    public List<Employee> listNotInIdList(Long accountSetId, List<Long> idList) {
        return bqf.selectFrom(qEmployee)
                .where(qEmployee.accountSetsId.eq(accountSetId)
                        .and(qEmployee.id.notIn(idList)))
                .orderBy(qEmployee.status.desc(), qEmployee.id.desc())
                .fetch();
    }

    /**
     * 查询不在列表且在当前账期没有工资记录的员工
     *
     * @param accountSetId 账套ID
     * @param idList 已存在的员工ID列表
     * @param period 账期
     * @return 符合条件的员工列表
     */
    public List<Employee> listNotInIdListAndPeriod(Long accountSetId, List<Long> idList, LocalDate period) {
        if (period == null) {
            // 如果账期为null，使用原有逻辑
            return listNotInIdList(accountSetId, idList);
        }

        // 查询在当前账期已有工资记录的员工ID
        List<Long> existingEmpIds = bqf.selectFrom(qSalaryFieldValue)
                .select(qSalaryFieldValue.empId)
                .where(qSalaryFieldValue.accountSetsId.eq(accountSetId)
                        .and(qSalaryFieldValue.period.eq(period)))
                .distinct()
                .fetch();

        // 合并已存在的员工ID列表和已有工资记录的员工ID
        Set<Long> excludeIds = new HashSet<>();
        if (CollUtil.isNotEmpty(idList)) {
            excludeIds.addAll(idList);
        }
        if (CollUtil.isNotEmpty(existingEmpIds)) {
            excludeIds.addAll(existingEmpIds);
        }

        // 查询不在排除列表中的员工
        if (CollUtil.isEmpty(excludeIds)) {
            return bqf.selectFrom(qEmployee)
                    .where(qEmployee.accountSetsId.eq(accountSetId))
                    .orderBy(qEmployee.status.desc(), qEmployee.id.desc())
                    .fetch();
        } else {
            return bqf.selectFrom(qEmployee)
                    .where(qEmployee.accountSetsId.eq(accountSetId)
                            .and(qEmployee.id.notIn(new ArrayList<>(excludeIds))))
                    .orderBy(qEmployee.status.desc(), qEmployee.id.desc())
                    .fetch();
        }
    }

    /**
     * 获取员工工资汇总信息
     * @param accountSetsId
     * @param period
     * @return
     */
    public GetEmployeeSalarySumInfoResp uaGetEmployeeSalarySumInfo(Long accountSetsId, LocalDate period) {
        GetEmployeeSalarySumInfoResp resp = new GetEmployeeSalarySumInfoResp();
        Long count = bqf.selectFrom(qEmployee)
                .select(qEmployee.count())
                .where(qEmployee.accountSetsId.eq(accountSetsId))
                .fetchFirst();
        resp.setEmployeeCount(count);

        List<Salary> salaryList = bqf.selectFrom(qSalary)
                .where(qSalary.accountSetsId.eq(accountSetsId).and(qSalary.period.eq(period)).and(qSalary.state.eq(Boolean.TRUE)))
                .fetch();
        if(CollUtil.isNotEmpty(salaryList)){
            AtomicReference<Double> salarySum = new AtomicReference<>(0.00);
            salaryList.forEach(item -> {
                JSONObject jsonObject = JSONObject.parseObject(JSONObject.toJSONString(item.getFieldValue()));
                Map<String, Object> map = (Map<String, Object>)jsonObject;
                map.forEach((k, v) -> {
                    Map<String, Object> vMap = (Map<String, Object>)v;
                    if("应发工资合计".equals(vMap.get("name"))){
                        salarySum.set(salarySum.get() + (Double.parseDouble(vMap.get("value").toString())));
                    }
                });
            });
            resp.setSalarySum(new BigDecimal(salarySum.get()));
        }
        return resp;
    }

    public static class QueryVo {
        private QEmployee qEmployee = QEmployee.employee;
        private BooleanBuilder builder = new BooleanBuilder();

        public void setDeptId(Long deptId) {
            if (deptId != null) {
                builder.and(qEmployee.departmentId.eq(deptId));
            }
        }

        public void setStatus(String status) {
            if (StrUtil.isNotEmpty(status)) {
                builder.and(qEmployee.status.eq(status));
            }
        }

        public void setName(String name) {
            if (StrUtil.isNotEmpty(name)) {
                builder.and(qEmployee.name.contains(name)
                        .or(qEmployee.telPhone.contains(name))
                        .or(qEmployee.idCard.contains(name)));
            }
        }

        public BooleanBuilder query() {
            return builder;
        }
    }


}
