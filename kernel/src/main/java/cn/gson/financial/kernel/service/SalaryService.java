package cn.gson.financial.kernel.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.gson.financial.kernel.common.CopierCopyUtils;
import cn.gson.financial.kernel.common.LocalDateRange;
import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.AuxiliaryProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.bean.FieldValue;
import cn.gson.financial.kernel.model.bean.Value;
import cn.gson.financial.kernel.model.dto.*;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.enums.DataType;
import cn.gson.financial.kernel.model.enums.FieldType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.individualTax.dto.AddIndividualTaxDto;
import cn.gson.financial.kernel.model.individualTax.dto.DeleteIndividualTaxDto;
import cn.gson.financial.kernel.model.individualTax.service.IndividualTaxService;
import cn.gson.financial.kernel.model.mapper.EmployeeMapper;
import cn.gson.financial.kernel.model.mapper.SalaryEmployeeMapper;
import cn.gson.financial.kernel.model.mapper.SalaryMapper;
import cn.gson.financial.kernel.model.repo.SalaryFieldValueRepository;
import cn.gson.financial.kernel.model.repo.SalaryRepository;
import cn.gson.financial.kernel.model.ua.resp.GetSalaryInfoListResp;
import cn.gson.financial.kernel.model.vo.EmployeeSocialVo;
import cn.gson.financial.kernel.model.vo.SalaryDetailVo;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.blazebit.persistence.PagedList;
import com.blazebit.persistence.querydsl.BlazeJPAQuery;
import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.googlecode.aviator.EvalMode;
import com.googlecode.aviator.Expression;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static cn.gson.financial.kernel.common.Constants.S_USER_KEY;

/**
 *
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 工资管理</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-07-28 14:04:18</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SalaryService extends AbsService implements AccountSetsProcessor, BackupProcessor, AuxiliaryProcessor {
    private final SalaryMapper salaryMapper;
    private final EmployeeMapper employeeMapper;
    private final AviatorEvaluatorInstance evaluator = AviatorEvaluator.newInstance(EvalMode.INTERPRETER);

    private final SalaryEmployeeMapper salaryEmployeeMapper;

    private final SalaryRepository salaryRepository;

    private final SalaryFieldValueRepository fieldValueRepository;
    private final QSalary qSalary = QSalary.salary;
    private final QCheckout qCheckout = QCheckout.checkout;
    private final QSalaryField qSalaryField = QSalaryField.salaryField;
    private final QSalaryVoucher qSalaryVoucher = QSalaryVoucher.salaryVoucher;
    private final QEmployee qEmployee = QEmployee.employee;
    private final QDepartment qDepartment = QDepartment.department;
    private final QSalaryFieldValue qSalaryFieldValue = QSalaryFieldValue.salaryFieldValue;
    private final QSalarySocialEmployee qSalarySocialEmployee = QSalarySocialEmployee.salarySocialEmployee;
    private final SalaryEmployeeService salaryEmployeeService;
    private final FinancialProcessService financialProcessService;
    private final IndividualTaxService individualTaxService;
    private final QAccountSets qAccountSets = QAccountSets.accountSets;
    private final QUserAccountSets qUserAccountSets = QUserAccountSets.userAccountSets;
    private final QUser qUser = QUser.user;
    private final QRole qRole = QRole.role;
    private final QIndividualTax qIndividualTax = QIndividualTax.individualTax;

    /**
     * 工资列表
     *
     * @param accountSetsId
     * @return
     */
    public PageResults<SalaryDto> list(Long accountSetsId, Page page, LocalDateRange dateRange) {
        BlazeJPAQuery<Long> vCount = bqf.selectFrom(qSalaryVoucher)
                .select(qSalaryVoucher.count())
                .where(qSalaryVoucher.accountSetsId.eq(qSalary.accountSetsId)
                        .and(qSalaryVoucher.salaryId.eq(qSalary.id)));
        BooleanExpression expression = qSalary.accountSetsId.eq(accountSetsId);

        if (!dateRange.isEmpty()) {
            expression = expression.and(qSalary.period.between(dateRange.getStart(), dateRange.getEnd()));
        }
        PagedList<Tuple> pagedList = bqf.selectFrom(qSalary)
                .select(qSalary, vCount)
                .where(expression)
                .orderBy(qSalary.period.desc(), qSalary.id.desc())
                .fetchPage(page.getOffset(), page.getOffsetEnd());
        List<SalaryDto> salaryDtos = pagedList.stream().map(tuple -> {
            SalaryDto dto = salaryMapper.toDto(tuple.get(qSalary));
            Long count = tuple.get(vCount);
            dto.setHasVoucher(count != null && count > 0);
            return dto;
        }).toList();
        return new PageResults<>(salaryDtos, page, pagedList.getTotalSize());
    }

    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qSalary)
                .where(qSalary.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qSalary)
                .where(qSalary.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<?> backupType() {
        return Salary.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {
        List<Salary> entityList = JSON.parseArray(json, Salary.class);
        salaryRepository.saveAll(entityList.stream()
                .peek(s -> s.setAccountSetsId(accountSetsId)).toList());
    }

    /**
     * 工资明细
     *
     * @param accountSetsId
     * @return
     */
    public SalaryDetailVo detail(Long accountSetsId, Long salaryId) {
        List<SalaryEmployeeDto> employeeDtos;
        SalaryDetailVo detailVo = new SalaryDetailVo();
        if (salaryId == null) {
            //所有员工
            employeeDtos = bqf.selectFrom(qEmployee)
                    .select(qDepartment.name, qEmployee)
                    .leftJoin(qDepartment).on(qDepartment.id.eq(qEmployee.departmentId).and(qEmployee.accountSetsId.eq(qDepartment.accountSetsId)))
                    .orderBy(qDepartment.code.castToNum(Integer.class).asc(), qEmployee.code.castToNum(Integer.class).asc())
                    .where(qEmployee.accountSetsId.eq(accountSetsId).and(qEmployee.status.eq("在职"))).stream().map(tuple -> {
                        SalaryEmployeeDto employeeDto = salaryEmployeeMapper.toDto(tuple.get(qEmployee));
                        employeeDto.setDepartmentName(tuple.get(qDepartment.name));
                        return employeeDto;
                    }).toList();

            detailVo.setSalaryFields(bqf.selectFrom(qSalaryField)
                    .where(qSalaryField.accountSetsId.eq(accountSetsId)
                            .and(qSalaryField.enabled.isTrue()))
                    .fetch());
        } else {
            BooleanExpression expression = qSalary.accountSetsId.eq(accountSetsId)
                    .and(qSalary.id.eq(salaryId));
            Salary salary = bqf.selectFrom(qSalary).where(expression).fetchFirst();
            employeeDtos = getSalaryEmployeeDtos(accountSetsId, salary.getId());
            this.calculateFieldValue(salary, employeeDtos, true);
            detailVo.setSalary(salary);
            detailVo.setSalaryFields(salary.getSalaryFields());
        }

        detailVo.setSalaryEmployees(employeeDtos);
        return detailVo;
    }

    private List<SalaryEmployeeDto> getSalaryEmployeeDtos(Long accountSetsId, Long salaryId) {
        return bqf.selectFrom(qSalaryFieldValue)
                .select(qDepartment.name, qEmployee, qSalaryFieldValue.fieldValue)
                .innerJoin(qEmployee).on(qSalaryFieldValue.empId.eq(qEmployee.id).and(qEmployee.accountSetsId.eq(qSalaryFieldValue.accountSetsId)))
                .leftJoin(qDepartment).on(qDepartment.id.eq(qEmployee.departmentId).and(qEmployee.accountSetsId.eq(qDepartment.accountSetsId)))
                .orderBy(qDepartment.code.castToNum(Integer.class).asc(), qEmployee.code.castToNum(Integer.class).asc())
                .where(qSalaryFieldValue.accountSetsId.eq(accountSetsId)
                        .and(qSalaryFieldValue.salaryId.eq(salaryId))).stream().map(tuple -> {
                    SalaryEmployeeDto employeeDto = salaryEmployeeMapper.toDto(tuple.get(qEmployee));
                    employeeDto.setDepartmentName(tuple.get(qDepartment.name));
                    employeeDto.setFieldValue(tuple.get(qSalaryFieldValue.fieldValue));
                    return employeeDto;
                }).toList();
    }

    /**
     * 保存工资信息
     *
     * @param accountSetsId
     * @param period
     * @param detailVo
     * @return
     */
    @Transactional
    public Salary save(Long accountSetsId, LocalDate period, SalaryDetailVo detailVo, Boolean flag) {
        if (flag) {
            //检查当前账期是否有工资，如果有工资且复核则不允许新增，查看该账期是否已报个税，如果已报个税则不允许新增
            long individualTaxCount = bqf.selectFrom(qIndividualTax)
                    .where(qIndividualTax.accountSetsId.eq(accountSetsId)
                            .and(qIndividualTax.period.eq(period))
                    ).fetchCount();
            Assert.isFalse(individualTaxCount != 0, period.format(DateTimeFormatter.ofPattern("yyyy年MM月")) + "个税已添加任务，不允许修改，如需修改联系群聊会计");
            long salaryCount = bqf.selectFrom(qSalary)
                    .where(qSalary.accountSetsId.eq(accountSetsId)
                            .and(qSalary.period.eq(period))
                            .and(qSalary.state.eq(true))).fetchCount();
            Assert.isFalse(salaryCount != 0, period.format(DateTimeFormatter.ofPattern("yyyy年MM月")) + "工资已复核，不允许新增，如需修改联系群聊会计");
            Assert.isTrue(bqf.selectFrom(qSalaryVoucher)
                    .where(qSalaryVoucher.accountSetsId.eq(accountSetsId)
                            .and(qSalaryVoucher.period.eq(period)))
                    .fetchCount() == 0, "已生成凭证不允进行此操作，如需修改联系群聊会计");

            Integer status = bqf.selectFrom(qCheckout)
                    .select(qCheckout.status)
                    .where(qCheckout.accountSetsId.eq(accountSetsId)
                            .and(qCheckout.checkDate.eq(period)))
                    .fetchFirst();
            if (status != null) {
                Assert.isFalse(status == 2, "期间已结账,不允许进行此操作，如需修改联系群聊会计");
            }
        }
        List<SalaryEmployeeDto> employeeDtos = detailVo.getSalaryEmployees();
        Salary salary;
        if (detailVo.getSalary() != null && detailVo.getSalary().getId() != null) {
            salary = bqf.selectFrom(qSalary)
                    .where(qSalary.accountSetsId.eq(accountSetsId)
                            .and(qSalary.id.eq(detailVo.getSalary().getId())))
                    .fetchFirst();
            Assert.isFalse(salary.getState(), period.format(DateTimeFormatter.ofPattern("yyyy年MM月")) + "工资已复核，不允许编辑~");
            //未符合删除原有数据，后保存最新数据
            jqf.delete(qSalaryFieldValue)
                    .where(qSalaryFieldValue.salaryId.eq(salary.getId())
                            .and(qSalaryFieldValue.accountSetsId.eq(accountSetsId)))
                    .execute();
            salary.setSalaryFields(detailVo.getSalaryFields());
        } else {
            // 新建工资表时，检查员工是否在当前账期已有工资记录
            if (CollUtil.isNotEmpty(employeeDtos) && period != null) {
                // 查询在当前账期已有工资记录的员工ID
                List<Long> existingEmpIds = bqf.selectFrom(qSalaryFieldValue)
                        .select(qSalaryFieldValue.empId)
                        .where(qSalaryFieldValue.accountSetsId.eq(accountSetsId)
                                .and(qSalaryFieldValue.period.eq(period)))
                        .distinct()
                        .fetch();

                if (CollUtil.isNotEmpty(existingEmpIds)) {
                    // 过滤掉已存在的员工
                    List<Long> existingEmpIdSet = new ArrayList<>(existingEmpIds);
                    employeeDtos = employeeDtos.stream()
                            .filter(emp -> !existingEmpIdSet.contains(emp.getId()))
                            .collect(Collectors.toList());
                }
            }

            // 如果过滤后没有员工，说明所有员工都已存在工资记录，不创建新的工资表
            if (CollUtil.isEmpty(employeeDtos)) {
                throw new ServiceException(period.format(DateTimeFormatter.ofPattern("yyyy年MM月")) + "所有员工都已存在工资记录，无需创建新的工资表~");
            }

            salary = new Salary();
            salary.setPeriod(period);
            salary.setYear(period.getYear());
            salary.setMonth(period.getMonthValue());
            salary.setState(false);
            salary.setSalaryFields(detailVo.getSalaryFields());
            salary.setId(snowflakeId.generate());
            salary.setAccountSetsId(accountSetsId);
            salaryRepository.saveAndFlush(salary);
        }

        this.calculateFieldValue(salary, employeeDtos, false);

        Salary finalSalary = salary;
        AtomicReference<FieldValue> totalValue = new AtomicReference<>(new FieldValue());
        List<SalaryFieldValue> fieldValueList = employeeDtos.stream().map(d -> {
            SalaryFieldValue sfv = new SalaryFieldValue();
            sfv.setPeriod(period);
            sfv.setSalaryId(finalSalary.getId());
            sfv.setEmpId(d.getId());
            sfv.setFieldValue(d.getFieldValue());
            sfv.setId(snowflakeId.generate());
            sfv.setAccountSetsId(accountSetsId);
            totalValue.get().add(d.getFieldValue());
            return sfv;
        }).toList();

        salary.setFieldValue(totalValue.get());
        salaryRepository.save(salary);
        fieldValueRepository.saveAll(fieldValueList);
        return salary;
    }


    /**
     * 计算职员薪资字段值
     *
     * @param salary
     * @param employeeDtos
     * @param isList
     */
    private void calculateFieldValue(Salary salary, List<SalaryEmployeeDto> employeeDtos, boolean isList) {
        Long accountSetsId = salary.getAccountSetsId();
        LocalDate period = salary.getPeriod();

        Map<Long, FieldValue> salaryFieldValueList = null;
        // 所有工资字段映射，后面统一用于给未赋值字段填默认值（包括新建和列表场景）
        Map<Long, SalaryField> fieldMap = null;
        List<SalaryField> salaryFields = salary.getSalaryFields();
        Map<Long, SalaryField> oldFieldMap = salaryFields.stream().collect(Collectors.toMap(SalaryField::getId, s -> s));
        // 没有字段或则未复核的时候，每次都用最新字段值
        if (CollUtil.isEmpty(salaryFields) || !salary.getState()) {
            List<SalaryField> fields = bqf.selectFrom(qSalaryField)
                    .where(qSalaryField.accountSetsId.eq(accountSetsId))
                    .orderBy(qSalaryField.weight.asc())
                    .fetch();
            for (SalaryField field : fields) {
                SalaryField old = oldFieldMap.get(field.getId());
                if (old != null) {
                    field.setEnabled(old.getEnabled());
                }
            }
            salary.setSalaryFields(fields);
        }

        // 所有自定义字段
        fieldMap = salary.getSalaryFields().stream().collect(Collectors.toMap(SalaryField::getId, s -> s));

        if (isList) {
            // 已有的字段值（仅列表场景需要从库里回填）
            salaryFieldValueList = bqf.selectFrom(qSalaryFieldValue)
                    .where(qSalaryFieldValue.accountSetsId.eq(accountSetsId)
                            .and(qSalaryFieldValue.salaryId.eq(salary.getId()))
                            .and(qSalaryFieldValue.period.eq(period)))
                    .fetch().stream().collect(Collectors.toMap(SalaryFieldValue::getEmpId, SalaryFieldValue::getFieldValue));
        }

        //员工社保信息
        EmployeeSocialVo employeeSocialVo = salaryEmployeeService.loadSocial(accountSetsId, employeeDtos.stream()
                .filter(SalaryEmployeeDto::getPurchase)
                .map(SalaryEmployeeDto::getId).toList());

        for (SalaryEmployeeDto employeeDto : employeeDtos) {
            if (isList) {
                FieldValue fieldValues = salaryFieldValueList.get(employeeDto.getId());
                if (fieldValues != null) {
                    for (String fieldId : fieldValues.keySet()) {
                        employeeDto.getFieldValue().put(fieldId, fieldValues.get(fieldId));
                    }
                }
            }

            // 未赋值 或 值为空 的字段（无论是列表还是新建），统一按默认值填全：
            // key 缺失、或值为 null / 空字符串时，数值/计算字段填 0，字符字段填 ""。
            // 这样空值不会在保存、复制上月工资、导出、个税下发等环节被原样带出去。
            for (SalaryField sf : fieldMap.values()) {
                String key = sf.getId().toString();
                Object defaultVal = DataType.字符.equals(sf.getDataType()) ? "" : BigDecimal.ZERO;
                Value val = employeeDto.getFieldValue().get(key);
                if (val == null) {
                    employeeDto.getFieldValue().put(key, new Value(sf.getFieldName(), defaultVal));
                } else if (val.getValue() == null || StrUtil.isBlank(val.getValue().toString())) {
                    val.setValue(defaultVal);
                }
            }

            if (CollUtil.isEmpty(employeeDto.getFieldValue().getSalaryFields())) {
                employeeDto.getFieldValue().setSalaryFields(salaryFields);
            }

            //先处理系统字段
            employeeDto.getFieldValue().getSalaryFields().stream().filter(sf -> FieldType.系统项目.equals(sf.getFieldType())).forEach(sf -> {
                BigDecimal val = BigDecimal.ZERO;
                if (sf.getEnabled()) {
                    if (employeeDto.getPurchase()) {
                        //社保取值字段名称
                        String socialName = sf.getSocialFieldType() + employeeSocialVo.getSocialName(sf.getSocialFieldId());
                        Map<String, BigDecimal> empSocialVal = employeeSocialVo.getEmpSocial(employeeDto.getId());
                        if (empSocialVal != null) {
                            val = Optional.ofNullable(empSocialVal.get(socialName)).orElse(val);
                        }
                    }
                }
                //不购买的社保的，涉及到社保字段都重置为0
                employeeDto.getFieldValue().put(sf.getId().toString(), new Value(sf.getFieldName(), val));
            });

            //再处理计算字段
            employeeDto.getFieldValue().getSalaryFields().stream().filter(sf -> DataType.计算.equals(sf.getDataType())).forEach(sf -> {
                Object val = BigDecimal.ZERO;
                if (sf.getEnabled() && StrUtil.isNotEmpty(sf.getFormula())) {
                    Map<String, Object> env = new HashMap<>();
                    env.put("$accountSetsId", accountSetsId);
                    env.put("$salaryField", sf);
                    env.put("$period", period);
                    //计算不出值就返回0
                    try {
                        //编译表达式
                        Expression expression = evaluator.compile(sf.getFormula());
                        //获取所有变量名称
                        List<String> variableFullNames = expression.getVariableFullNames();
                        //变量赋值
                        env = variableFullNames.stream().collect(Collectors.toMap(v -> v, v -> {
                            //先取自定义字段值
                            Optional<Value> value = employeeDto.getFieldValue().getVal(v);
                            Object o;
                            //再取系统字段值
                            if (value.isPresent()) {
                                o = value.get().getValue();
                            } else {
                                o = employeeSocialVo.getEmpSocialVal(employeeDto.getId(), v);
                            }
                            //都取不到值就返回0
                            return Optional.ofNullable(o).orElse(BigDecimal.ZERO);
                        }));

                        val = Optional.ofNullable(expression.execute(env)).orElse(BigDecimal.ZERO);
                    } catch (Exception e) {
                        log.info("\n字段：{}\n表达式：{}\n变量值：{}", sf.getFieldName(), sf.getFormula(), env, e);
                        employeeDto.getErrors().add(StrUtil.format("\n错误信息:{}\n字段：{}\n表达式：{}\n参数值：{}", e.getMessage(), sf.getFieldName(), sf.getFormula(), env));
                    }
                }

                employeeDto.getFieldValue().put(sf.getId().toString(), new Value(sf.getFieldName(), val));
            });
        }
    }

    /**
     * 状态变更
     *
     * @param accountSetsId
     * @param state
     * @param salaryId
     */
    @Transactional
    public void review(Long accountSetsId, Boolean state, Long salaryId, Boolean flag) {
        if (flag) {
            Salary salary = bqf.selectFrom(qSalary)
                    .where(qSalary.accountSetsId.eq(accountSetsId)
                            .and(qSalary.id.eq(salaryId))
                    ).fetchFirst();
            LocalDate period = salary.getPeriod();
            //检查当前账期是否有工资，如果有工资且复核则不允许新增，查看该账期是否已报个税，如果已报个税则不允许新增 ，该账期是否结转，该账期是否有工资生成凭证 ，有则不能修改
            long individualTaxCount = bqf.selectFrom(qIndividualTax)
                    .where(qIndividualTax.accountSetsId.eq(accountSetsId)
                            .and(qIndividualTax.period.eq(period))
                            ).fetchCount();
            Assert.isFalse(individualTaxCount != 0, period.format(DateTimeFormatter.ofPattern("yyyy年MM月")) + "个税已添加任务，不允许修改，如需修改联系群聊会计");
            Integer status = bqf.selectFrom(qCheckout)
                    .select(qCheckout.status)
                    .where(qCheckout.accountSetsId.eq(accountSetsId)
                            .and(qCheckout.checkYear.eq(salary.getYear()))
                            .and(qCheckout.checkMonth.eq(salary.getMonth())))
                    .fetchFirst();
            if (status != null) {
                Assert.isFalse(status == 2, "期间已结账,不允许进行此操作，如需修改联系群聊会计");
            }
            Assert.isTrue(bqf.selectFrom(qSalaryVoucher)
                    .where(qSalaryVoucher.accountSetsId.eq(accountSetsId)
                            .and(qSalaryVoucher.period.eq(period)))
                    .fetchCount() == 0, "已生成凭证不允进行此操作，如需修改联系群聊会计");
        }
        Salary salaryOne = bqf.selectFrom(qSalary).where(qSalary.id.eq(salaryId)).fetchFirst();
        FinancialProcessDTO financialProcessDTO = new FinancialProcessDTO();
        financialProcessDTO.setAccountingPeriod(String.valueOf(salaryOne.getPeriod()));
        financialProcessDTO.setAccountSetsId(salaryOne.getAccountSetsId());

        LocalDate now = LocalDate.now();
        LocalDate lastMonthFirstDay = now.minusMonths(1).withDayOfMonth(1);
        String period = lastMonthFirstDay.format(DateTimeFormatter.ISO_LOCAL_DATE);
        if (state) {
            //添加到个税任务表中
            AddIndividualTaxDto addIndividualTaxDto = new AddIndividualTaxDto();
            addIndividualTaxDto.setPeriod(period);
            addIndividualTaxDto.setAccountSetsIdList(Collections.singletonList(accountSetsId));
            if (period.equals(String.valueOf(salaryOne.getPeriod()))) {
                individualTaxService.addIndividualTaxList(addIndividualTaxDto);
            }
            financialProcessDTO.setIsPayrollProcessed("是");
        } else {
            //从个税任务表中删除
            DeleteIndividualTaxDto deleteIndividualTaxDto = new DeleteIndividualTaxDto();
            deleteIndividualTaxDto.setPeriod(period);
            deleteIndividualTaxDto.setAccountSetsIdList(Collections.singletonList(accountSetsId));
            if (period.equals(String.valueOf(salaryOne.getPeriod()))) {
                individualTaxService.deleteIndividualTaxList(deleteIndividualTaxDto);
            }
            financialProcessDTO.setIsPayrollProcessed("否");
        }

        Salary salary = salaryRepository.getReferenceById(new PrimaryKey(salaryId, accountSetsId));
        Integer status = bqf.selectFrom(qCheckout)
                .select(qCheckout.status)
                .where(qCheckout.accountSetsId.eq(accountSetsId)
                        .and(qCheckout.checkYear.eq(salary.getYear()))
                        .and(qCheckout.checkMonth.eq(salary.getMonth())))
                .fetchFirst();
        if (status != null) {
            Assert.isFalse(status == 2, "期间已结账,不允许进行此操作~");
        }
        Assert.isTrue(bqf.selectFrom(qSalaryVoucher)
                .where(qSalaryVoucher.accountSetsId.eq(accountSetsId)
                        .and(qSalaryVoucher.salaryId.eq(salaryId)))
                .fetchCount() == 0, "已生成凭证不允进行此操作~");

        jqf.update(qSalary)
                .set(qSalary.state, state)
                .where(qSalary.accountSetsId.eq(accountSetsId)
                        .and(qSalary.id.eq(salaryId)))
                .execute();
        financialProcessService.updateFinancialProcess(financialProcessDTO);
    }

    /**
     * 获取导出和打印数据
     *
     * @param accountSetsId
     * @param dateRange
     * @return
     */
    public List<SalaryExportDetailDto> exportDetail(Long accountSetsId, LocalDateRange dateRange) {
        List<Salary> salaries = bqf.selectFrom(qSalary)
                .where(qSalary.accountSetsId.eq(accountSetsId)
                        .and(qSalary.period.between(dateRange.getStart(), dateRange.getEnd())))
                .orderBy(qSalary.period.asc(), qSalary.id.asc())
                .fetch();
        List<SalaryExportDetailDto> detailDtos = new ArrayList<>();
        for (Salary salary : salaries) {
            SalaryExportDetailDto detailDto = new SalaryExportDetailDto();
            detailDto.setSalaryFields(salary.getSalaryFields());
            detailDto.setPeriod(salary.getPeriod());
            detailDto.setFieldVals(
                    bqf.selectFrom(qSalaryFieldValue)
                            .select(qSalaryFieldValue.fieldValue, qDepartment.name, qEmployee)
                            .innerJoin(qEmployee).on(qEmployee.id.eq(qSalaryFieldValue.empId).and(qSalaryFieldValue.accountSetsId.eq(qEmployee.accountSetsId)))
                            .leftJoin(qDepartment).on(qEmployee.departmentId.eq(qDepartment.id).and(qDepartment.accountSetsId.eq(qEmployee.accountSetsId)))
                            .where(qSalaryFieldValue.salaryId.eq(salary.getId())
                                    .and(qSalaryFieldValue.accountSetsId.eq(salary.getAccountSetsId())))
                            .fetch().stream().map(tuple -> {
                                SalaryExportDetailFieldValDto dto = new SalaryExportDetailFieldValDto();
                                dto.setFieldValue(tuple.get(qSalaryFieldValue.fieldValue));
                                EmployeeDto employee = employeeMapper.toDto(tuple.get(qEmployee));
                                employee.setDepartmentName(tuple.get(qDepartment.name));
                                dto.setEmployee(employee);
                                return dto;
                            }).toList());

            // 导出前补全字段值：员工缺失某字段、或字段值为 null / 空字符串时，
            // 数值/计算类字段填 "0"，字符类字段填 ""，避免 JXLS 模板
            // ${rd.fieldValue[h.id+""].value} 取到 null 导致整表无法导出。
            fillBlankFieldValues(detailDto.getSalaryFields(), detailDto.getFieldVals());

            detailDtos.add(detailDto);
        }
        return detailDtos;
    }

    /**
     * 工资导出补空：保证每个员工对每个工资字段都有非空的 {@link Value}。
     * <p>数值 / 计算类字段缺失或为空时补 "0"，字符类字段补 ""，
     * 防止导出模板对 null 取属性报错导致整张薪资表导不出来。
     */
    private void fillBlankFieldValues(List<SalaryField> salaryFields,
                                      List<SalaryExportDetailFieldValDto> fieldVals) {
        if (CollUtil.isEmpty(salaryFields) || CollUtil.isEmpty(fieldVals)) {
            return;
        }
        for (SalaryExportDetailFieldValDto dto : fieldVals) {
            FieldValue fieldValue = dto.getFieldValue();
            if (fieldValue == null) {
                fieldValue = new FieldValue();
                dto.setFieldValue(fieldValue);
            }
            for (SalaryField field : salaryFields) {
                if (field == null || field.getId() == null) {
                    continue;
                }
                String key = field.getId().toString();
                // 字符类字段补空串，其余（数值 / 计算）补 0
                String defaultVal = DataType.字符.equals(field.getDataType()) ? "" : "0";
                Value val = fieldValue.get(key);
                if (val == null) {
                    fieldValue.put(key, new Value(field.getFieldName(), defaultVal));
                } else if (val.getValue() == null || StrUtil.isBlank(val.getValue().toString())) {
                    val.setValue(defaultVal);
                }
            }
        }
    }

    /**
     * 辅助项目删除
     *
     * @param primaryKey
     * @param details
     */
    @Override
    public void deleted(PrimaryKey primaryKey, AcDetails details) {
        if (AccountingCategoryType.EMPLOYEE.equals(details.getCategoryType())) {
            Assert.isTrue(bqf.selectFrom(qSalaryFieldValue)
                    .where(qSalaryFieldValue.empId.eq(primaryKey.getId())
                            .and(qSalaryFieldValue.accountSetsId.eq(primaryKey.getAccountSetsId())))
                    .fetchCount() == 0, details.getName() + "已有工资记录，不能删除~");

            jqf.delete(qSalarySocialEmployee)
                    .where(qSalarySocialEmployee.empId.eq(primaryKey.getId())
                            .and(qSalarySocialEmployee.accountSetsId.eq(primaryKey.getAccountSetsId())))
                    .execute();
        }
    }

    /**
     * 获取最后一次工资表月份，没有，则返回账套开始月份
     *
     * @param accountSetsId
     * @return
     */
    public LocalDate lastDate(Long accountSetsId) {
        LocalDate localDate = bqf.selectFrom(qSalary)
                .select(qSalary.period)
                .where(qSalary.accountSetsId.eq(accountSetsId))
                .orderBy(qSalary.period.desc())
                .fetchFirst();
        return localDate;
    }

    /**
     * 删除工资表
     *
     * @param accountSetsId
     * @param salaryId
     */
    @Transactional
    public void delete(Long accountSetsId, Long salaryId, Boolean flag) {
        if (flag) {
            Salary salary = bqf.selectFrom(qSalary)
                    .where(qSalary.accountSetsId.eq(accountSetsId)
                            .and(qSalary.id.eq(salaryId)))
                    .fetchFirst();
            LocalDate period = salary.getPeriod();
            //检查当前账期是否有工资，如果有工资且复核则不允许新增，查看该账期是否已报个税，如果已报个税则不允许新增 ，该账期是否结转，该账期是否有工资生成凭证 ，有则不能修改
            long salaryCount = bqf.selectFrom(qSalary)
                    .where(qSalary.accountSetsId.eq(accountSetsId)
                            .and(qSalary.period.eq(period))
                            .and(qSalary.state.eq(true))).fetchCount();
            Assert.isFalse(salaryCount != 0, period.format(DateTimeFormatter.ofPattern("yyyy年MM月")) + "工资已复核，不允许修改，如需修改联系群聊会计");
            long individualTaxCount = bqf.selectFrom(qIndividualTax)
                    .where(qIndividualTax.accountSetsId.eq(accountSetsId)
                            .and(qIndividualTax.period.eq(period))
                            .and(qIndividualTax.result.eq(2))).fetchCount();
            Assert.isFalse(individualTaxCount != 0, period.format(DateTimeFormatter.ofPattern("yyyy年MM月")) + "个税已申报，不允许修改，如需修改联系群聊会计");
            Integer status = bqf.selectFrom(qCheckout)
                    .select(qCheckout.status)
                    .where(qCheckout.accountSetsId.eq(accountSetsId)
                            .and(qCheckout.checkYear.eq(salary.getYear()))
                            .and(qCheckout.checkMonth.eq(salary.getMonth())))
                    .fetchFirst();
            if (status != null) {
                Assert.isFalse(status == 2, "期间已结账,不允许进行此操作，如需修改联系群聊会计");
            }
            Assert.isTrue(bqf.selectFrom(qSalaryVoucher)
                    .where(qSalaryVoucher.accountSetsId.eq(accountSetsId)
                            .and(qSalaryVoucher.salaryId.eq(salaryId)))
                    .fetchCount() == 0, "已生成凭证不允进行此操作，如需修改联系群聊会计");
        }
        jqf.delete(qSalaryFieldValue)
                .where(qSalaryFieldValue.salaryId.eq(salaryId)
                        .and(qSalaryFieldValue.accountSetsId.eq(accountSetsId)))
                .execute();

        jqf.delete(qSalary)
                .where(qSalary.id.eq(salaryId)
                        .and(qSalary.accountSetsId.eq(accountSetsId)))
                .execute();
    }

    /**
     * 检查期间是否有工资表未生成凭证
     *
     * @param accountSetsId
     * @param year
     * @param month
     * @return
     */
    public boolean salaryCheck(Long accountSetsId, Integer year, Integer month) {
        return bqf.selectFrom(qSalary)
                .select(qSalary, qSalaryVoucher.id)
                .leftJoin(qSalaryVoucher).on(qSalary.accountSetsId.eq(qSalaryVoucher.accountSetsId)
                        .and(qSalary.id.eq(qSalaryVoucher.salaryId)))
                .where(qSalary.year.eq(year).and(qSalary.month.eq(month)).and(qSalary.accountSetsId.eq(accountSetsId)))
                .fetch().stream()
                // FN-FIX-CHECKOUT-PRECHECK-DATA-AWARE：只对「应发工资合计 > 0」的工资要求凭证。
                // 0 金额工资 autogen 跳过不建凭证（SalaryVoucherTemplateService.autoGenerateVouchers 的 allZero 短路），
                // 若仍要求其有凭证则永远过不了结转前检查、关不了账。allZero ⟹ 应发合计=0，故 autogen 的跳过集
                // 恒为本过滤排除集的子集，二者一致、无「建不出又要求有」死循环。有金额工资无凭证仍判未通过（不放宽真缺凭证）。
                .filter(t -> {
                    Salary s = t.get(qSalary);
                    return s != null && s.getFieldValue() != null
                            && s.getFieldValue().salarySum().compareTo(java.math.BigDecimal.ZERO) > 0;
                })
                .noneMatch(t -> t.get(qSalaryVoucher.id) == null);
    }

    /**
     * 获取工资基本信息
     *
     * @param accountSetsId
     * @return
     */
    public List<GetSalaryInfoListResp> uaGetSalaryInfoList(Long accountSetsId, LocalDate period) {
        List<GetSalaryInfoListResp> respList = new ArrayList<>();
        if (ObjectUtils.isNotEmpty(accountSetsId)) {
            List<Salary> salaryList = bqf.selectFrom(qSalary)
                    .where(qSalary.accountSetsId.eq(accountSetsId).and(qSalary.period.eq(period)))
                    .fetch();

            if (salaryList.size() > 0) {
                respList = salaryList.stream().map(item -> {
                    GetSalaryInfoListResp resp = CopierCopyUtils.convertTo(item, GetSalaryInfoListResp.class);
                    // FN-FIX-CHECKOUT-PRECHECK-DATA-AWARE：复用 FieldValue.salarySum() 共享解析（应发工资合计），
                    // 与 salaryCheck 同一真相源，非数字/缺字段安全返 0。
                    if (item.getFieldValue() != null) {
                        resp.setSalarySum(item.getFieldValue().salarySum().doubleValue());
                    }
                    return resp;
                }).collect(Collectors.toList());
            }
        }
        return respList;
    }

    /**
     * 查询账套列表（用于批量复制工资，支持筛选有工资/无工资的账套）
     *
     * @param userId    用户ID
     * @param period    账期（格式：yyyy-MM，如果为空则使用当前账期）
     * @param hasSalary 是否有工资（true:有工资, false:无工资, null:全部）
     * @return 包含账套列表和统计信息的Map
     */
    public Map<String, Object> getAccountSetsForBatchCopy(Long userId, String period, Boolean hasSalary) {
        // 解析账期
        LocalDate targetPeriod = null;
        if (StrUtil.isNotEmpty(period)) {
            try {
                targetPeriod = LocalDate.parse(period + "-01");
            } catch (Exception e) {
                log.warn("账期格式错误: {}", period);
            }
        }

        // 获取用户关联的所有账套（只显示未停用的账套）
        List<Tuple> tuples = bqf.selectFrom(qUserAccountSets)
                .select(qAccountSets, qUser.realName.coalesce(qUser.nickname), qRole.name)
                .leftJoin(qAccountSets).on(qAccountSets.id.eq(qUserAccountSets.accountSetsId))
                .leftJoin(qUser).on(qUser.id.eq(qAccountSets.creatorId))
                .leftJoin(qRole).on(qRole.id.eq(qUserAccountSets.roleId).and(qUserAccountSets.accountSetsId.eq(qRole.accountSetsId)))
                .where(qUserAccountSets.userId.eq(userId)
                        .and(qAccountSets.deactivated.isFalse().or(qAccountSets.deactivated.isNull()))) // 只显示未停用的账套
                .orderBy(qUserAccountSets.current.desc(), qAccountSets.id.desc())
                .fetch();

        if (CollUtil.isEmpty(tuples)) {
            Map<String, Object> result = new HashMap<>();
            result.put("accountSets", new ArrayList<>());
            result.put("totalCount", 0L);
            result.put("hasSalaryCount", 0L);
            result.put("noSalaryCount", 0L);
            return result;
        }

        // 获取所有账套ID
        List<Long> accountSetsIds = tuples.stream()
                .map(tuple -> tuple.get(qAccountSets).getId())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 如果指定了账期，查询该账期有工资的账套ID（只统计已复核的工资）
        Set<Long> accountSetsWithSalary = new HashSet<>();
        if (targetPeriod != null) {
            List<Long> salaryAccountSetsIds = bqf.selectFrom(qSalary)
                    .select(qSalary.accountSetsId)
                    .where(qSalary.accountSetsId.in(accountSetsIds)
                            .and(qSalary.period.eq(targetPeriod))
                            .and(qSalary.state.isTrue())) // 只统计已复核的工资
                    .distinct()
                    .fetch();
            accountSetsWithSalary.addAll(salaryAccountSetsIds);
        }

        // 查询当前月份（系统当前月份）有工资的账套ID（只统计已复核的工资）
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        List<Long> currentMonthSalaryAccountSetsIds = bqf.selectFrom(qSalary)
                .select(qSalary.accountSetsId)
                .where(qSalary.accountSetsId.in(accountSetsIds)
                        .and(qSalary.period.eq(currentMonth))
                        .and(qSalary.state.isTrue())) // 只统计已复核的工资
                .distinct()
                .fetch();
        Set<Long> accountSetsWithCurrentMonthSalary = new HashSet<>(currentMonthSalaryAccountSetsIds);

        // 统计信息
        long hasSalaryCount = accountSetsWithSalary.size();
        long noSalaryCount = accountSetsIds.size() - hasSalaryCount;

        // 根据筛选条件过滤账套
        List<AccountSetsDto> filteredAccountSets = new ArrayList<>();
        for (Tuple tuple : tuples) {
            Long accountSetsId = tuple.get(qAccountSets).getId();
            if (accountSetsId == null) {
                continue;
            }

            boolean accountHasSalary = accountSetsWithSalary.contains(accountSetsId);
            boolean accountHasCurrentMonthSalary = accountSetsWithCurrentMonthSalary.contains(accountSetsId);

            // 根据筛选条件决定是否包含
            if (hasSalary == null) {
                // 全部显示
            } else if (hasSalary && !accountHasSalary) {
                // 只要有工资的，但当前账套没有
                continue;
            } else if (!hasSalary && accountHasSalary) {
                // 只要无工资的，但当前账套有
                continue;
            }

            AccountSetsDto dto = new AccountSetsDto();
            AccountSets accountSets = tuple.get(qAccountSets);
            dto.setId(accountSets.getId());
            dto.setCompanyName(accountSets.getCompanyName());
            dto.setCurrentAccountDate(accountSets.getCurrentAccountDate());
            dto.setCreatorName(tuple.get(qUser.realName.coalesce(qUser.nickname)));
            dto.setRoleName(tuple.get(qRole.name));
            dto.setHasSalary(accountHasSalary); // 标记目标账期是否有工资
            dto.setHasCurrentMonthSalary(accountHasCurrentMonthSalary); // 标记当前月份是否有工资

            filteredAccountSets.add(dto);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("accountSets", filteredAccountSets);
        result.put("totalCount", (long) filteredAccountSets.size());
        result.put("hasSalaryCount", hasSalaryCount);
        result.put("noSalaryCount", noSalaryCount);
        return result;
    }

    /**
     * 批量复制工资
     * 复制逻辑：
     * 1. 查找目标账期之前最近的一个有工资的月份（优先已复核，其次未复核）
     * 2. 如果之前月份都没有工资，则生成0工资（使用基础员工列表，按默认值/计算公式）
     * 3. 如果目标账期已有某个员工的工资，则跳过该员工，只复制其他员工
     * 4. 复制完成后自动复核
     *
     * @param userId         用户ID
     * @param accountSetsIds 账套ID列表
     * @param period         目标账期（格式：yyyy-MM）
     * @return 批量复制结果（成功和失败的账套列表）
     */
    @Transactional
    public Map<String, Object> batchCopyLastMonthSalary(Long userId, List<Long> accountSetsIds, String period) {
        if (CollUtil.isEmpty(accountSetsIds)) {
            throw new ServiceException("请选择至少一个账套");
        }

        // 解析目标账期
        LocalDate targetPeriod;
        try {
            targetPeriod = LocalDate.parse(period + "-01");
        } catch (Exception e) {
            throw new ServiceException("账期格式错误: " + period);
        }

        // 验证用户是否有权限操作这些账套
        List<Long> userAccountSetsIds = bqf.selectFrom(qUserAccountSets)
                .select(qUserAccountSets.accountSetsId)
                .where(qUserAccountSets.userId.eq(userId)
                        .and(qUserAccountSets.accountSetsId.in(accountSetsIds)))
                .fetch();

        if (userAccountSetsIds.size() != accountSetsIds.size()) {
            throw new ServiceException("您没有权限操作部分账套");
        }

        List<Map<String, Object>> successList = new ArrayList<>();
        List<Map<String, Object>> failList = new ArrayList<>();

        for (Long accountSetsId : accountSetsIds) {
            Map<String, Object> result = new HashMap<>();
            result.put("accountSetsId", accountSetsId);

            try {
                // 获取账套信息
                AccountSets accountSets = bqf.selectFrom(qAccountSets)
                        .where(qAccountSets.id.eq(accountSetsId))
                        .fetchFirst();
                if (accountSets == null) {
                    result.put("message", "账套不存在");
                    failList.add(result);
                    continue;
                }
                result.put("companyName", accountSets.getCompanyName());

                // 查询目标账期之前最近的一个有工资的月份（优先查询已复核的，如果没有则查询未复核的）
                Salary nearestSalary = bqf.selectFrom(qSalary)
                        .where(qSalary.accountSetsId.eq(accountSetsId)
                                .and(qSalary.period.lt(targetPeriod))
                                .and(qSalary.state.isTrue())) // 优先查询已复核的
                        .orderBy(qSalary.period.desc(), qSalary.id.desc())
                        .fetchFirst();
                // 如果之前没有已复核的工资表，则查询未复核的
                if (nearestSalary == null) {
                    nearestSalary = bqf.selectFrom(qSalary)
                            .where(qSalary.accountSetsId.eq(accountSetsId)
                                    .and(qSalary.period.lt(targetPeriod)))
                            .orderBy(qSalary.period.desc(), qSalary.id.desc())
                            .fetchFirst();
                }

                // 最近工资明细（如果存在工资表则按工资表取，否则为 null，将生成0工资）
                SalaryDetailVo nearestDetail = null;
                if (nearestSalary != null) {
                    nearestDetail = detail(accountSetsId, nearestSalary.getId());
                }

                // 基础员工列表：使用"新建工资表"的默认员工列表，确保所有在职员工都能生成记录
                SalaryDetailVo baseDetail = detail(accountSetsId, null);

                // 检查是否有在职员工
                List<SalaryEmployeeDto> baseEmployees = baseDetail.getSalaryEmployees();
                if (CollUtil.isEmpty(baseEmployees)) {
                    result.put("message", "无人员，跳过");
                    failList.add(result);
                    continue;
                }

                // 查询当前账期已有工资的员工ID
                List<Long> existingEmpIds = bqf.selectFrom(qSalaryFieldValue)
                        .select(qSalaryFieldValue.empId)
                        .where(qSalaryFieldValue.accountSetsId.eq(accountSetsId)
                                .and(qSalaryFieldValue.period.eq(targetPeriod)))
                        .distinct()
                        .fetch();

                // 构造本次要复制的员工列表：
                // 1）先取所有基础员工（相当于手工新建工资时的员工列表，已过滤在职员工）
                // 2）如果之前月份存在工资表，则用之前月份的员工工资数据覆盖对应员工，其余员工按"无工资"处理（走默认值/计算公式，相当于按 0 复制）
                // 3）只包含当前仍在职的员工，排除离职员工

                // 获取当前所有在职员工的ID集合，用于过滤离职员工
                Set<Long> activeEmployeeIds = baseEmployees.stream()
                        .map(SalaryEmployeeDto::getId)
                        .collect(Collectors.toSet());

                Map<Long, SalaryEmployeeDto> nearestEmpMap = new HashMap<>();
                if (nearestDetail != null && CollUtil.isNotEmpty(nearestDetail.getSalaryEmployees())) {
                    // 将之前月份有工资的员工数据放入Map，但只保留当前仍在职的员工
                    for (SalaryEmployeeDto nearestEmp : nearestDetail.getSalaryEmployees()) {
                        // 只包含当前仍在职的员工，排除离职员工
                        if (activeEmployeeIds.contains(nearestEmp.getId())) {
                            nearestEmpMap.put(nearestEmp.getId(), nearestEmp);
                        }
                    }
                }

                // 确保所有基础员工（在职员工）都被包含：
                // - 如果之前月份有该员工的工资数据，使用之前月份的数据
                // - 如果之前月份没有该员工的工资数据，使用基础员工数据（会按0工资处理）
                List<SalaryEmployeeDto> allEmployees = new ArrayList<>();
                for (SalaryEmployeeDto baseEmp : baseEmployees) {
                    SalaryEmployeeDto empToUse = nearestEmpMap.get(baseEmp.getId());
                    if (empToUse != null) {
                        // 之前月份有该员工的工资数据，使用之前月份的数据
                        allEmployees.add(empToUse);
                    } else {
                        // 之前月份没有该员工的工资数据，使用基础员工数据（fieldValue会在calculateFieldValue中填充默认值，按0工资处理）
                        allEmployees.add(baseEmp);
                    }
                }

                // 过滤掉在目标账期已有工资的员工（如果目标账期已有该员工的工资，则跳过该员工，只复制其他员工）
                List<SalaryEmployeeDto> filteredEmployees = allEmployees.stream()
                        .filter(emp -> !existingEmpIds.contains(emp.getId()))
                        .collect(Collectors.toList());

                // 查询目标账期所有未复核的工资表
                List<Salary> targetPeriodUnreviewedSalaries = bqf.selectFrom(qSalary)
                        .where(qSalary.accountSetsId.eq(accountSetsId)
                                .and(qSalary.period.eq(targetPeriod))
                                .and(qSalary.state.isFalse())) // 只查询未复核的工资表
                        .fetch();

                // 如果所有员工都有工资记录，检查是否有未复核的工资表
                if (CollUtil.isEmpty(filteredEmployees)) {
                    // 如果有未复核的工资表，复核它们
                    if (CollUtil.isNotEmpty(targetPeriodUnreviewedSalaries)) {
                        for (Salary salary : targetPeriodUnreviewedSalaries) {
                            review(accountSetsId, true, salary.getId(), Boolean.FALSE);
                        }
                        result.put("message", "所有员工在当前账期已有工资记录，已复核所有未复核的工资表");
                        result.put("employeeCount", 0);
                        result.put("excludedCount", allEmployees.size());
                        result.put("reviewedCount", targetPeriodUnreviewedSalaries.size());
                        successList.add(result);
                    } else {
                        // 所有员工都有工资记录且都已复核，跳过
                        result.put("message", "所有员工在当前账期已有工资记录且已复核，跳过");
                        failList.add(result);
                    }
                    continue;
                }

                // 创建新的工资详情VO
                SalaryDetailVo newDetailVo = new SalaryDetailVo();
                newDetailVo.setSalaryEmployees(filteredEmployees);
                // 使用基础工资项配置，确保与当前账套工资项保持一致
                newDetailVo.setSalaryFields(baseDetail.getSalaryFields());
                newDetailVo.setSalary(new Salary()); // 新建工资表

                // 保存工资表
                Salary newSalary = save(accountSetsId, targetPeriod, newDetailVo, Boolean.FALSE);

                // 复制完成后，自动复核目标账期的所有工资表（包括刚刚创建的和之前已存在但未复核的）
                // 重新查询，因为可能刚刚创建了新的工资表
                List<Salary> targetPeriodSalaries = bqf.selectFrom(qSalary)
                        .where(qSalary.accountSetsId.eq(accountSetsId)
                                .and(qSalary.period.eq(targetPeriod))
                                .and(qSalary.state.isFalse())) // 只复核未复核的工资表
                        .fetch();

                for (Salary salary : targetPeriodSalaries) {
                    review(accountSetsId, true, salary.getId(), Boolean.FALSE);
                }

                result.put("message", "复制并自动复核成功");
                result.put("employeeCount", filteredEmployees.size());
                result.put("excludedCount", allEmployees.size() - filteredEmployees.size());
                result.put("reviewedCount", targetPeriodSalaries.size()); // 记录复核的工资表数量
                successList.add(result);

            } catch (Exception e) {
                log.error("批量复制工资失败，账套ID: {}", accountSetsId, e);
                result.put("message", "复制失败: " + e.getMessage());
                failList.add(result);
            }
        }

        Map<String, Object> batchResult = new HashMap<>();
        batchResult.put("success", successList);
        batchResult.put("fail", failList);
        batchResult.put("successCount", successList.size());
        batchResult.put("failCount", failList.size());
        batchResult.put("totalCount", accountSetsIds.size());

        return batchResult;
    }
}

