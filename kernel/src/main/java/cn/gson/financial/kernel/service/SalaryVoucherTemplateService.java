package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionAccountSetId;
import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.bean.FieldValue;
import cn.gson.financial.kernel.model.bean.Value;
import cn.gson.financial.kernel.model.dto.SalaryVoucherTemplateDto;
import cn.gson.financial.kernel.model.dto.SubjectDto;
import cn.gson.financial.kernel.model.dto.VoucherDetailsDto;
import cn.gson.financial.kernel.model.dto.VoucherDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.mapper.SalaryVoucherTemplateMapper;
import cn.gson.financial.kernel.model.repo.SalaryVoucherTemplateRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.BeanCopier;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.NumberUtil;
import com.alibaba.fastjson2.JSON;
import com.querydsl.core.types.EntityPath;
import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.beanutils.PropertyUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestBody;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-11 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-11</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SalaryVoucherTemplateService extends AbsService implements AccountSetsProcessor, BackupProcessor {
    private final SalaryVoucherTemplateMapper salaryVoucherTemplateMapper;

    private final SalaryVoucherTemplateRepository svtp;

    private final QVoucherWord qVoucherWord = QVoucherWord.voucherWord;

    private final QSalary qSalary = QSalary.salary;

    private final QSalaryFieldValue qSalaryFieldValue = QSalaryFieldValue.salaryFieldValue;

    private final QEmployee qEmployee = QEmployee.employee;

    private final QSalaryVoucher qSalaryVoucher = QSalaryVoucher.salaryVoucher;

    private final QSalaryVoucherTemplate qSalaryVoucherTemplate = QSalaryVoucherTemplate.salaryVoucherTemplate;

    private final QAccountSets qAccountSets = QAccountSets.accountSets;

    private final QSubject qSubject = QSubject.subject;

    private final SubjectService subjectService;

    private final VoucherService voucherService;

    private final BalanceCalcService balanceCalcService;

    private final SalaryVoucherService salaryVoucherService;

    private final QSalaryField qSalaryField = QSalaryField.salaryField;

    public List<SalaryVoucherTemplateDto> list(Long accountSetsId) {
        BooleanExpression expression = qSalaryVoucherTemplate.accountSetsId.eq(accountSetsId);
        return bqf.selectFrom(qSalaryVoucherTemplate)
                .select(qSalaryVoucherTemplate, qVoucherWord.word)
                .leftJoin(qVoucherWord).on(qVoucherWord.id.eq(qSalaryVoucherTemplate.voucherWord).and(qVoucherWord.accountSetsId.eq(accountSetsId)))
                .where(expression)
                .orderBy(qSalaryVoucherTemplate.id.asc())
                .fetch().stream().map(tuple -> {
                    SalaryVoucherTemplateDto dto = salaryVoucherTemplateMapper.toDto(tuple.get(qSalaryVoucherTemplate));
                    dto.setWord(tuple.get(qVoucherWord.word));
                    return dto;
                }).collect(Collectors.toList());
    }
    @Transactional(readOnly = false)
    public void testAddVoucherTemplate() {
        bqf.selectFrom(qAccountSets).fetch().forEach((item) -> {
            //遍历所有账套，根据账套Id找出所对应的凭证号id
            Long accountSetsId = item.getId();
            //编码方式,二级科目编码
            Integer encoding = item.getEncoding().get(0) + item.getEncoding().get(1) + item.getEncoding().get(2) + item.getEncoding().get(3);

            //先添加科目，根据名称匹配，对于没有科目的先建立科目，建立科目根据二级科目中最大编号+1来规定科目编码
            //查询该账套下所需科目一级科目编码
            //5602 职工-工资   2211 职工-工资  2241 代扣-社保  2241 法人-垫付
//            List<String> stringList1 = bqf.selectFrom(qSubject).select(qSubject.code).where(qSubject.code.like("5602%").and(qSubject.accountSetsId.eq(accountSetsId))).fetch();
//            List<String> stringList2 = bqf.selectFrom(qSubject).select(qSubject.code).where(qSubject.code.like("2211%").and(qSubject.accountSetsId.eq(accountSetsId))).fetch();
//            List<String> stringList3 = bqf.selectFrom(qSubject).select(qSubject.code).where(qSubject.code.like("2241%").and(qSubject.accountSetsId.eq(accountSetsId))).fetch();
            Long longList1 = bqf.selectFrom(qSubject).select(qSubject.id).where(qSubject.code.eq("5602").and(qSubject.accountSetsId.eq(accountSetsId))).fetchFirst();
            Long longList2 = bqf.selectFrom(qSubject).select(qSubject.id).where(qSubject.code.eq("2211").and(qSubject.accountSetsId.eq(accountSetsId))).fetchFirst();
            Long longList3 = bqf.selectFrom(qSubject).select(qSubject.id).where(qSubject.code.eq("2241").and(qSubject.accountSetsId.eq(accountSetsId))).fetchFirst();
            // 计算二级段长度，当只存在4位一级科目时，按编码格式拼接二级编码
            int secondLen = item.getEncoding().get(1);

            String parent5602 = "5602";

            String parent2211 = "2211";

            String parent2241 = "2241";

            Subject subject = new Subject();
            subject.setAccountSetsId(accountSetsId);
            subject.setStatus(true);
            subject.setLevel(2);
            subject.setCash(false);
            subject.setSystemDefault(false);
            List<Subject> subjectList = new ArrayList<>();

                String nextCode1 = nextAvailableChildCode(accountSetsId, parent5602, secondLen);
                subjectList.add(createSubject(subject, "损益", nextCode1, "职工-工资", "借", longList1, "管理费用_职工-工资", encoding));
                String nextCode2 = nextAvailableChildCode(accountSetsId, parent2211, secondLen);
                subjectList.add(createSubject(subject, "负债", nextCode2, "职工-工资", "贷", longList2, "应付职工薪酬_职工-工资", encoding));
                String nextCode3 = nextAvailableChildCode(accountSetsId, parent2241, secondLen);
                subjectList.add(createSubject(subject, "负债", nextCode3, "代扣-社保", "贷", longList3, "其他应付款_代扣-社保", encoding));
                String nextCode4 = nextAvailableChildCode(accountSetsId, parent2241, secondLen);
                subjectList.add(createSubject(subject, "负债", nextCode4, "法人-垫付", "贷", longList3, "其他应付款_法人-垫付", encoding));

            //接下来创建工资凭证
            System.out.println(accountSetsId);
            List<SalaryVoucherTemplate.VoucherItems> voucherItemsList = new ArrayList<>();
            subjectList.forEach(sub->{
                SalaryVoucherTemplate.VoucherItems voucherItems = new SalaryVoucherTemplate.VoucherItems();
                voucherItems.setSubjectTitle(sub.getFullName());
                voucherItems.setSubjectId(String.valueOf(sub.getId()));
                //根据账套id和工资字段名称，查询工资字段id
                if ("管理费用_职工-工资".equals(sub.getFullName())){
                    Long fieldId = bqf.selectFrom(qSalaryField).select(qSalaryField.id).where(qSalaryField.accountSetsId.eq(accountSetsId).and(qSalaryField.fieldName.eq("岗位工资"))).fetchFirst();
                    voucherItems.setDirection("借");
                    voucherItems.setSalaryFieldId(String.valueOf(fieldId));
                    voucherItems.setSalaryFieldTitle("岗位工资");
                    voucherItemsList.add(voucherItems);
                }
                if ("应付职工薪酬_职工-工资".equals(sub.getFullName())){
                    Long fieldId = bqf.selectFrom(qSalaryField).select(qSalaryField.id).where(qSalaryField.accountSetsId.eq(accountSetsId).and(qSalaryField.fieldName.eq("岗位工资"))).fetchFirst();
                    voucherItems.setDirection("借");
                    voucherItems.setSalaryFieldId(String.valueOf(fieldId));
                    voucherItems.setSalaryFieldTitle("岗位工资");
                    voucherItemsList.add(voucherItems);
                    SalaryVoucherTemplate.VoucherItems voucherItems1 = new SalaryVoucherTemplate.VoucherItems();
                    voucherItems1.setSubjectTitle(sub.getFullName());
                    voucherItems1.setSubjectId(String.valueOf(sub.getId()));
                    voucherItems1.setDirection("贷");
                    voucherItems1.setSalaryFieldId(String.valueOf(fieldId));
                    voucherItems1.setSalaryFieldTitle("岗位工资");
                    voucherItemsList.add(voucherItems1);
                }
                if ("其他应付款_代扣-社保".equals(sub.getFullName())){
                    Long fieldId = bqf.selectFrom(qSalaryField).select(qSalaryField.id).where(qSalaryField.accountSetsId.eq(accountSetsId).and(qSalaryField.fieldName.eq("社保缴纳"))).fetchFirst();
                    voucherItems.setDirection("贷");
                    voucherItems.setSalaryFieldId(String.valueOf(fieldId));
                    voucherItems.setSalaryFieldTitle("社保缴纳");
                    voucherItemsList.add(voucherItems);
                }
                if ("其他应付款_法人-垫付".equals(sub.getFullName())){
                    Long fieldId = bqf.selectFrom(qSalaryField).select(qSalaryField.id).where(qSalaryField.accountSetsId.eq(accountSetsId).and(qSalaryField.fieldName.eq("实发金额"))).fetchFirst();
                    voucherItems.setDirection("贷");
                    voucherItems.setSalaryFieldId(String.valueOf(fieldId));
                    voucherItems.setSalaryFieldTitle("实发金额");
                    voucherItemsList.add(voucherItems);
                }
            });

            SalaryVoucherTemplate salaryVoucherTemplate = new SalaryVoucherTemplate();
            salaryVoucherTemplate.setAccountSetsId(accountSetsId);
            salaryVoucherTemplate.setIsAccrual(false);
            salaryVoucherTemplate.setDef(false);
            salaryVoucherTemplate.setItems(voucherItemsList);
            salaryVoucherTemplate.setTitle("计提发放工资");
            Long voucherWordId =  bqf.selectFrom(qVoucherWord).select(qVoucherWord.id).where(qVoucherWord.accountSetsId.eq(accountSetsId).and(qVoucherWord.printTitle.eq("记账凭证"))).fetchFirst();
            salaryVoucherTemplate.setVoucherWord(voucherWordId);
            testSaveSalaryVoucherTemplate(salaryVoucherTemplate);
        });

    }
    @Transactional(readOnly = false)
    public Subject createSubject(Subject subject, String type, String code, String name, String balanceDirection, Long parentId,String fullName,Integer encLen) {
            // 基于传入的基础 subject（仅作模板）创建一个全新的实例，避免集合中复用同一引用
            Subject newSubject = new Subject();
            BeanUtil.copyProperties(subject, newSubject, CopyOptions.create().ignoreNullValue());
            newSubject.setType(type);
            newSubject.setCode(code);
            newSubject.setName(name);
            newSubject.setBalanceDirection(balanceDirection);
            newSubject.setParentId(parentId);
            newSubject.setFullName(fullName);

        return subjectService.testSaveSubject(newSubject, encLen);
    }

    /**
     * 基于父级前缀与子段长度，生成下一个子科目编码。
     * 规则：在所有以 parentPrefix 开头的编码中，截取 parentPrefix 后的 childLen 位作为本级段，
     * 找到数值最大者 + 1；若不存在合法子段，则从 1 开始。结果为 parentPrefix + 递增子段（零填充至 childLen 位）。
     */
    private String nextChildSubjectCode(List<String> subjectCodes, String parentPrefix, int childLen) {
        if (parentPrefix == null || childLen <= 0) {
            return null;
        }
        if (subjectCodes == null || subjectCodes.isEmpty()) {
            return parentPrefix + String.format("%0" + childLen + "d", 1);
        }

        int start = parentPrefix.length();
        Long maxVal = null;
        for (String code : subjectCodes) {
            if (code == null || !code.startsWith(parentPrefix)) {
                continue;
            }
            if (code.length() < start + childLen) {
                // 只有父级或不足以包含一个完整的子段，忽略
                continue;
            }
            String seg = code.substring(start, start + childLen);
            if (!seg.chars().allMatch(Character::isDigit)) {
                continue;
            }
            try {
                long v = Long.parseLong(seg);
                if (maxVal == null || v > maxVal) {
                    maxVal = v;
                }
            } catch (NumberFormatException ignore) {
            }
        }

        long next = (maxVal == null ? 1 : maxVal + 1);
        return parentPrefix + String.format("%0" + childLen + "d", next);
    }

    /**
     * 基于数据库现状，生成下一个可用的子科目编码；若存在冲突则递增直到可用。
     */
    private String nextAvailableChildCode(Long accountSetsId, String parentPrefix, int childLen) {
        List<String> subjectCodes = bqf.selectFrom(qSubject)
                .select(qSubject.code)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.code.like(parentPrefix + "%")))
                .fetch();

        String code = nextChildSubjectCode(subjectCodes, parentPrefix, childLen);
        if (code == null) return null;
        while (bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.code.eq(code)))
                .fetchCount() > 0) {
            int start = parentPrefix.length();
            String seg = code.substring(start, start + childLen);
            long v = Long.parseLong(seg) + 1;
            code = parentPrefix + String.format("%0" + childLen + "d", v);
        }
        return code;
    }

    @Transactional
    public SalaryVoucherTemplate save(SalaryVoucherTemplate salaryVoucherTemplate) {
        if (salaryVoucherTemplate.getId() != null) {
            SalaryVoucherTemplate original = svtp.getReferenceById(salaryVoucherTemplate.getPrimaryKey());
            if (!original.getTitle().equals(salaryVoucherTemplate.getTitle())) {
                Assert.isTrue(bqf.selectFrom(qSalaryVoucherTemplate)
                        .where(qSalaryVoucherTemplate.accountSetsId.eq(salaryVoucherTemplate.getAccountSetsId())
                                .and(qSalaryVoucherTemplate.id.ne(salaryVoucherTemplate.getId()))
                                .and(qSalaryVoucherTemplate.title.eq(salaryVoucherTemplate.getTitle())))
                        .fetchCount() == 0, "名称不能重复~");
            }
            BeanUtil.copyProperties(salaryVoucherTemplate, original, CopyOptions.create().ignoreNullValue());
            salaryVoucherTemplate = svtp.save(original);
        } else {
            Assert.isTrue(bqf.selectFrom(qSalaryVoucherTemplate)
                    .where(qSalaryVoucherTemplate.accountSetsId.eq(salaryVoucherTemplate.getAccountSetsId())
                            .and(qSalaryVoucherTemplate.title.eq(salaryVoucherTemplate.getTitle())))
                    .fetchCount() == 0, "名称不能重复~");
            salaryVoucherTemplate = svtp.save(salaryVoucherTemplate);
        }
        return salaryVoucherTemplate;
    }

    @Transactional
    public SalaryVoucherTemplate  testSaveSalaryVoucherTemplate(SalaryVoucherTemplate salaryVoucherTemplate) {
        if (salaryVoucherTemplate.getId() != null) {
            SalaryVoucherTemplate original = svtp.getReferenceById(salaryVoucherTemplate.getPrimaryKey());
            if (!original.getTitle().equals(salaryVoucherTemplate.getTitle())) {
//                Assert.isTrue(bqf.selectFrom(qSalaryVoucherTemplate)
//                        .where(qSalaryVoucherTemplate.accountSetsId.eq(salaryVoucherTemplate.getAccountSetsId())
//                                .and(qSalaryVoucherTemplate.id.ne(salaryVoucherTemplate.getId()))
//                                .and(qSalaryVoucherTemplate.title.eq(salaryVoucherTemplate.getTitle())))
//                        .fetchCount() == 0, "名称不能重复~");
                if (bqf.selectFrom(qSalaryVoucherTemplate)
                  .where(qSalaryVoucherTemplate.accountSetsId.eq(salaryVoucherTemplate.getAccountSetsId())
                           .and(qSalaryVoucherTemplate.id.ne(salaryVoucherTemplate.getId()))
                          .and(qSalaryVoucherTemplate.title.eq(salaryVoucherTemplate.getTitle())))
                        .fetchCount() != 0){
                    return null;
                }
            }
            BeanUtil.copyProperties(salaryVoucherTemplate, original, CopyOptions.create().ignoreNullValue());
            salaryVoucherTemplate = svtp.save(original);
        } else {
//            Assert.isTrue(bqf.selectFrom(qSalaryVoucherTemplate)
//                    .where(qSalaryVoucherTemplate.accountSetsId.eq(salaryVoucherTemplate.getAccountSetsId())
//                            .and(qSalaryVoucherTemplate.title.eq(salaryVoucherTemplate.getTitle())))
//                    .fetchCount() == 0, "名称不能重复~");
            if (bqf.selectFrom(qSalaryVoucherTemplate)
                    .where(qSalaryVoucherTemplate.accountSetsId.eq(salaryVoucherTemplate.getAccountSetsId())
                            .and(qSalaryVoucherTemplate.title.eq(salaryVoucherTemplate.getTitle())))
                    .fetchCount() != 0){
                return  null;
            }
            salaryVoucherTemplate = svtp.save(salaryVoucherTemplate);
        }
        return salaryVoucherTemplate;
    }



    @Transactional
    public void delete(PrimaryKey primaryKey) {
        svtp.deleteById(primaryKey);
        jqf.delete(qSalaryVoucher)
                .where(qSalaryVoucher.accountSetsId.eq(primaryKey.getAccountSetsId())
                        .and(qSalaryVoucher.templateId.eq(primaryKey.getId())))
                .execute();
    }

    /**
     * 账套数据初始化
     *
     * @param accountSets
     * @param accountSetsTemplate
     */
    @Override
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        List<SalaryVoucherTemplate> fieldSchedules = bqf.selectFrom(qSalaryVoucherTemplate)
                .where(qSalaryVoucherTemplate.accountSetsId.eq(accountSetsTemplate.getId()))
                .fetch();

        if (CollUtil.isNotEmpty(fieldSchedules)) {
            svtp.saveAll(fieldSchedules.stream().map(s -> {
                bqf.detach(s);
                SalaryVoucherTemplate bean = BeanUtil.toBean(s, SalaryVoucherTemplate.class);
                bean.setAccountSetsId(accountSets.getId());
                return bean;
            }).toList());
        }
    }

    /**
     * 账套数据清理
     *
     * @param accountSetsId
     */
    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qSalaryVoucherTemplate)
                .where(qSalaryVoucherTemplate.accountSetsId.eq(accountSetsId))
                .execute();
    }

    /**
     * 备份
     *
     * @param accountSetsId
     * @return
     */
    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qSalaryVoucherTemplate)
                .where(qSalaryVoucherTemplate.accountSetsId.eq(accountSetsId))
                .fetch());
    }

    /**
     * 备份类型
     *
     * @return
     */
    @Override
    public Class<?> backupType() {
        return SalaryVoucherTemplate.class;
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
        List<SalaryVoucherTemplate> salaryFieldSchedules = JSON.parseArray(json, SalaryVoucherTemplate.class);
        svtp.saveAll(salaryFieldSchedules.stream().peek(s -> s.setAccountSetsId(accountSetsId)).toList());
    }

    /**
     * 根据模板配置，获取模板科目数据
     *
     * @param accountSetId
     * @param templateId
     * @param period
     * @param salaryId
     * @return
     */
    public List<SalaryVoucherTemplate.VoucherItems> data(Long accountSetId, Long templateId, LocalDate period, Long salaryId) {
        SalaryVoucherTemplate voucherTemplate = svtp.getReferenceById(new PrimaryKey(templateId, accountSetId));
        Salary salary = bqf.selectFrom(qSalary)
                .where(qSalary.accountSetsId.eq(accountSetId)
                        .and(qSalary.id.eq(salaryId)))
                .fetchFirst();
        //计提处理
        Map<Long, List<FieldValue>> valuesMap = new HashMap<>();
        //所有计提ID
        if (voucherTemplate.getIsAccrual()) {
            bqf.selectFrom(qSalaryFieldValue)
                    .select(qSalaryFieldValue.fieldValue, qEmployee.provisionId)
                    .innerJoin(qEmployee).on(qEmployee.id.eq(qSalaryFieldValue.empId).and(qEmployee.accountSetsId.eq(qSalaryFieldValue.accountSetsId)))
                    .where(qSalaryFieldValue.accountSetsId.eq(accountSetId)
                            .and(qEmployee.provisionId.isNotNull())
                            .and(qSalaryFieldValue.period.eq(period)))
                    .fetch().forEach(tuple -> {
                        //计提id
                        Long provisionId = tuple.get(qEmployee.provisionId);
                        List<FieldValue> fieldValues = valuesMap.computeIfAbsent(provisionId, k -> new ArrayList<>());
                        fieldValues.add(tuple.get(qSalaryFieldValue.fieldValue));
                    });
        }
        // 工资项目合计值
        FieldValue fieldValue = salary.getFieldValue();
        // 模板项目
        for (SalaryVoucherTemplate.VoucherItems item : voucherTemplate.getItems()) {
            Value value = fieldValue.get(item.getSalaryFieldId());
            if (voucherTemplate.getIsAccrual()) {
                if (valuesMap.containsKey(Long.valueOf(item.getSubjectId()))) {
                    BigDecimal amount = BigDecimal.ZERO;
                    for (FieldValue f : valuesMap.get(Long.valueOf(item.getSubjectId()))) {
                        amount = NumberUtil.add(NumberUtil.toBigDecimal(f.get(item.getSalaryFieldId()).getValue().toString()), amount);
                    }
                    item.setAmount(amount);
                } else if (value != null) {
                    item.setAmount(NumberUtil.toBigDecimal(value.getValue().toString()));
                } else {
                    item.setAmount(BigDecimal.ZERO);
                }
            } else if (value != null) {
                item.setAmount(NumberUtil.toBigDecimal(value.getValue().toString()));
            }
            item.setSummary(period.format(DateTimeFormatter.ofPattern("yyyy年MM月")) + voucherTemplate.getTitle() + "-" + item.getSalaryFieldTitle());
        }
        return voucherTemplate.getItems().stream().filter(it -> !NumberUtil.equals(it.getAmount(), BigDecimal.ZERO)).toList();
    }

    /**
     * 任务工作台做账：自动为当前账期生成工资凭证。
     * <p>
     * 逻辑：按 title=「计提发放工资」找模板（XW OpenAPI 约定），若当期已复核的工资存在且该模板尚未生成凭证，则自动生成。
     *
     * @param accountSetId 账套 ID
     * @param period       账期（当月第一天）
     * @return 结果 map：type / status / msg / generated
     */
    public static final String AUTO_GEN_TEMPLATE_TITLE = "计提发放工资";

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> autoGenerateVouchers(Long accountSetId, LocalDate period) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", "salary");
        try {
            // 1. 获取账套
            AccountSets accountSets = bqf.selectFrom(qAccountSets)
                    .where(qAccountSets.id.eq(accountSetId))
                    .fetchFirst();
            if (accountSets == null) {
                r.put("status", "error");
                r.put("msg", "工资凭证：账套不存在");
                return r;
            }

            // 2. 查找当期已复核工资（state = true）
            Salary salary = bqf.selectFrom(qSalary)
                    .where(qSalary.accountSetsId.eq(accountSetId)
                            .and(qSalary.period.eq(period))
                            .and(qSalary.state.eq(true)))
                    .fetchFirst();
            if (salary == null) {
                r.put("status", "skipped");
                r.put("msg", "工资凭证：当期无已复核工资，跳过");
                return r;
            }

            // 2b. 工资合计为0时直接完成，不生成凭证
            FieldValue totalFieldValue = salary.getFieldValue();
            boolean allZero = true;
            if (totalFieldValue != null) {
                for (Value v : totalFieldValue.values()) {
                    if (v != null && v.getValue() != null) {
                        try {
                            BigDecimal bd = new BigDecimal(v.getValue().toString());
                            if (bd.compareTo(BigDecimal.ZERO) != 0) { allZero = false; break; }
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (allZero) {
                r.put("status", "done");
                r.put("msg", "工资凭证：工资为0，无需生成凭证");
                r.put("generated", 0);
                return r;
            }

            // 3. 按 title 匹配「计提发放工资」合并模板（XW OpenAPI 约定的单条合并模板）
            List<SalaryVoucherTemplateDto> templates = list(accountSetId).stream()
                    .filter(t -> AUTO_GEN_TEMPLATE_TITLE.equals(t.getTitle()))
                    .collect(Collectors.toList());
            // FN-SALARY-AUTOGEN-SELF-HEAL：缺模板不再直接 skip——先自愈（自动建合并模板）再重新匹配
            boolean templateAutoCreated = false;
            Map<String, Object> templateEnsure = null;
            if (CollUtil.isEmpty(templates)) {
                templateEnsure = ensureAutoGenTemplate(accountSetId);
                if (Boolean.TRUE.equals(templateEnsure.get("created"))) {
                    templateAutoCreated = true;
                    templates = list(accountSetId).stream()
                            .filter(t -> AUTO_GEN_TEMPLATE_TITLE.equals(t.getTitle()))
                            .collect(Collectors.toList());
                }
            }
            if (CollUtil.isEmpty(templates)) {
                // 自愈也建不出（真缺凭证字/核心科目）→ skipped 并说原因，不假成功
                r.put("status", "skipped");
                String reason = templateEnsure != null ? String.valueOf(templateEnsure.get("reason")) : "no_template";
                r.put("msg", "工资凭证：未配置 title=\"" + AUTO_GEN_TEMPLATE_TITLE + "\" 模板，自动建模板未成功（" + reason + "），跳过");
                if (templateEnsure != null) r.put("templateEnsure", templateEnsure);
                return r;
            }

            int generated = 0;
            int alreadyHad = 0;
            List<String> errors = new ArrayList<>();
            List<Long> voucherIds = new ArrayList<>();

            for (SalaryVoucherTemplateDto template : templates) {
                // 计提/发放均在当月生成凭证
                LocalDate voucherDate = period;

                // 检查是否已生成
                Map<Long, SalaryVoucher> existing = salaryVoucherService.loadList(
                        accountSetId, List.of(template.getId()), voucherDate, salary.getId());
                if (existing.containsKey(template.getId())) {
                    alreadyHad++;
                    continue;
                }

                try {
                    // 获取模板各行金额
                    List<SalaryVoucherTemplate.VoucherItems> items = data(accountSetId, template.getId(), period, salary.getId());
                    if (CollUtil.isEmpty(items)) continue;

                    // data() sets item.amount + item.direction; compute totals from those
                    BigDecimal totalDebit = items.stream()
                            .filter(it -> "借".equals(it.getDirection()) && it.getAmount() != null)
                            .map(SalaryVoucherTemplate.VoucherItems::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal totalCredit = items.stream()
                            .filter(it -> "贷".equals(it.getDirection()) && it.getAmount() != null)
                            .map(SalaryVoucherTemplate.VoucherItems::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    // 借贷均为零时跳过（无工资数据）
                    if (totalDebit.compareTo(BigDecimal.ZERO) == 0 && totalCredit.compareTo(BigDecimal.ZERO) == 0) continue;

                    // 获取凭证号
                    String word = template.getWord() != null ? template.getWord() : "记";
                    int code = voucherService.loadCode(accountSetId, word, voucherDate);

                    // 构建凭证明细（非末级科目自动降级到第一个末级子科目）
                    List<VoucherDetailsDto> details = new ArrayList<>();
                    for (SalaryVoucherTemplate.VoucherItems item : items) {
                        Long subjectId = Long.parseLong(item.getSubjectId());
                        Subject subject = subjectService.findById(accountSetId, subjectId);
                        if (subject != null && !Boolean.TRUE.equals(subject.getLeaf())) {
                            Subject leaf = subjectService.findFirstLeafChild(accountSetId, subject.getId());
                            if (leaf != null) {
                                log.info("工资凭证科目降级: {}({}) → {}({})",
                                        subject.getCode(), subject.getName(), leaf.getCode(), leaf.getName());
                                subjectId = leaf.getId();
                            }
                        }
                        VoucherDetailsDto detail = new VoucherDetailsDto();
                        detail.setSubjectId(subjectId);
                        detail.setSummary(item.getSummary());
                        BigDecimal amt = item.getAmount() != null ? item.getAmount() : BigDecimal.ZERO;
                        if ("借".equals(item.getDirection())) {
                            detail.setDebitAmount(amt);
                        } else {
                            detail.setCreditAmount(amt);
                        }
                        details.add(detail);
                    }

                    // 构建 VoucherDto
                    VoucherDto voucherDto = new VoucherDto();
                    voucherDto.setWord(word);
                    voucherDto.setCode(code);
                    voucherDto.setVoucherDate(voucherDate);
                    voucherDto.setDebitAmount(totalDebit);
                    voucherDto.setCreditAmount(totalCredit);
                    voucherDto.setAccountSetsId(accountSetId);
                    voucherDto.setDetails(details);

                    // 保存凭证
                    VoucherDto saved = voucherService.save(voucherDto, accountSets, true);

                    // 保存工资凭证关联记录
                    SalaryVoucher salaryVoucher = new SalaryVoucher();
                    salaryVoucher.setVoucherId(saved.getId());
                    salaryVoucher.setPeriod(voucherDate);
                    salaryVoucher.setTemplateId(template.getId());
                    salaryVoucher.setYear(voucherDate.getYear());
                    salaryVoucher.setMonth(voucherDate.getMonthValue());
                    salaryVoucher.setAmount(totalDebit);
                    salaryVoucher.setAccountSetsId(accountSetId);
                    salaryVoucher.setSalaryId(salary.getId());
                    salaryVoucherService.save(salaryVoucher);

                    generated++;
                    voucherIds.add(saved.getId());
                } catch (Exception e) {
                    log.warn("工资凭证模板[{}]生成失败: accountSetId={}", template.getId(), accountSetId, e);
                    errors.add(e.getMessage());
                }
            }

            // FN-FIX-SALARY-AUTOGEN-SYNC-RECALC：openapi 工资 autogen 的余额重算改同步——
            // 所有工资凭证生成后、接口返回前把余额算落定，杜绝 xiaowei 后续 verify（OpenApiCheckoutController:583
            // 走 calcSynchronization）撞上工资这次的飞行异步重算而读到半成品余额行（假报 LOSS）。
            // 同时移出循环只算一次（当前合并模板只 1 张，行为等价；未来多模板时更稳）。
            // 网页手工 generate 路径不动（无下游紧接 verify，保持异步）。
            if (generated > 0) {
                // FN-FIX-SALARY-AUTOGEN-REVIEW-STATUS：自动生成的工资凭证默认即审（APPROVED，audit_member_id=0），
                // 与 XW 取消审批环节口径对齐 —— 否则每个有工资账套关账都被 PENDING 卡住、会计被迫逐户手动审核。
                // 在余额重算前批量审，状态落定后再算余额（审核仅改 audit_member_id/locked，不影响余额）。
                voucherService.systemAudit(accountSetId, voucherIds);
                try {
                    balanceCalcService.calcSynchronization(accountSetId, YearMonth.from(period));
                } catch (RuntimeException e) {
                    // 不静默：余额未落定必须可观测，交外层统一处理（避免返回时余额还没算完）
                    log.error("工资凭证已生成但余额同步重算失败: accountSetId={} period={}", accountSetId, period, e);
                    throw e;
                }
            }

            if (!errors.isEmpty()) {
                r.put("status", "warn");
                r.put("msg", String.format("工资凭证：生成 %d 条，已有 %d 条跳过，失败 %d 条", generated, alreadyHad, errors.size()));
            } else if (generated == 0 && alreadyHad == 0) {
                r.put("status", "skipped");
                r.put("msg", "工资凭证：无需生成（无有效金额）");
            } else {
                r.put("status", "done");
                r.put("msg", String.format("工资凭证：生成 %d 条，已有 %d 条跳过", generated, alreadyHad));
            }
            r.put("generated", generated);
            // FN-NEW-OPENAPI-SALARY-AUTOGEN: 暴露 XW 审计所需的 alreadyHad / voucherIds / errors
            r.put("alreadyHad", alreadyHad);
            r.put("voucherIds", voucherIds);
            r.put("errors", errors);
            // FN-SALARY-AUTOGEN-SELF-HEAL: 本次自愈建了模板 → 带出复核项（自动建的科目 + 未绑定字段），自动建≠免复核
            if (templateAutoCreated && templateEnsure != null) {
                r.put("templateAutoCreated", true);
                r.put("autoCreatedSubjects", templateEnsure.get("autoCreatedSubjects"));
                r.put("unboundFields", templateEnsure.get("unboundFields"));
                r.put("templateId", templateEnsure.get("templateId"));
            }
        } catch (Exception e) {
            log.error("工资凭证自动生成失败: accountSetId={}", accountSetId, e);
            r.put("status", "error");
            r.put("msg", "工资凭证生成失败：" + e.getMessage());
        }
        return r;
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-NEW-OPENAPI-SALARY-AUTOGEN: 默认模板兜底初始化
    //
    // 为没建过 SalaryVoucherTemplate 的账套，自动建一对 def=true 的默认模板：
    //   - 计提工资（isAccrual=true）：借 管理费用_职工-工资 / 贷 应付职工薪酬_职工-工资
    //   - 发放工资（isAccrual=false）：借 应付职工薪酬_职工-工资 / 贷 银行存款（+ 代扣社保 + 代扣个税）
    //
    // 缺少标准科目时按现有 createSubject 模式自动补建（5602 / 2211 / 2241）；
    // 银行存款（1002）和工资字段不强制存在 —— 缺失时降级，至少保证 计提 + 发放借 应付职工薪酬 这条
    // 主线可用。
    //
    // 幂等：已有 def=true 模板时不动；已有同 title 模板时不重复建（沿用现有 testSaveSalaryVoucherTemplate
    // 的 "名称不能重复" 语义并加返回 null 旁路）。
    // ═══════════════════════════════════════════════════════════════
    private static final String DEFAULT_ACCRUAL_TITLE  = "计提工资（默认）";
    private static final String DEFAULT_PAYOUT_TITLE   = "发放工资（默认）";

    /** 工资字段降级匹配序列（实发借方 / 实发贷方 / 计提主额） */
    private static final String[] FIELD_GROSS_CANDIDATES = {"应发金额", "应发合计", "应发工资", "岗位工资"};
    private static final String[] FIELD_NET_CANDIDATES   = {"实发金额", "实发合计", "实发工资"};
    private static final String[] FIELD_SOCIAL_CANDIDATES = {"社保缴纳", "代扣社保", "社保扣款"};
    private static final String[] FIELD_TAX_CANDIDATES   = {"个人所得税", "个税", "代扣个税"};

    /**
     * FN-SALARY-AUTOGEN-SELF-HEAL：为该账套确保存在 autogen 用的单条<b>合并</b>模板
     * （title={@link #AUTO_GEN_TEMPLATE_TITLE} 计提发放工资、is_accrual=false）。复用 initDefault 的零件
     * （findOrCreateSubject / findFirstLeafByCodePrefix / findSalaryFieldByCandidates / item，<b>不写死字段</b>），
     * 计提段(借管理费用/贷应付) + 发放段(借应付/贷银行+代扣社保+代扣个税) 合一。
     * <p>幂等：已存在同 title 模板 → 不重建（created=false, reason=already_exists）。
     * 缺核心科目(5602/2211)/凭证字 → 不强建，记日志并返回 created=false + reason。
     * <p>自动建的科目（autoCreatedSubjects）+ 未绑定的工资字段（unboundFields）随结果带出，提示会计复核（自动建≠免复核）。
     *
     * @return {accountSetsId, created, reason?, templateId?, autoCreatedSubjects[], unboundFields[]}
     */
    @Transactional
    public Map<String, Object> ensureAutoGenTemplate(Long accountSetsId) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("accountSetsId", accountSetsId);
        r.put("autoCreatedSubjects", new ArrayList<>());
        r.put("unboundFields", new ArrayList<>());

        // 幂等：已有 title='计提发放工资' → 不重建
        if (templateTitleExists(accountSetsId, AUTO_GEN_TEMPLATE_TITLE)) {
            r.put("created", false);
            r.put("reason", "already_exists");
            return r;
        }
        AccountSets as = bqf.selectFrom(qAccountSets).where(qAccountSets.id.eq(accountSetsId)).fetchFirst();
        if (as == null) {
            r.put("created", false);
            r.put("reason", "account_not_found");
            return r;
        }

        // 凭证字（首选"记账凭证"，否则该账套第一条）
        Long voucherWordId = bqf.selectFrom(qVoucherWord).select(qVoucherWord.id)
                .where(qVoucherWord.accountSetsId.eq(accountSetsId).and(qVoucherWord.printTitle.eq("记账凭证")))
                .fetchFirst();
        if (voucherWordId == null) {
            voucherWordId = bqf.selectFrom(qVoucherWord).select(qVoucherWord.id)
                    .where(qVoucherWord.accountSetsId.eq(accountSetsId)).orderBy(qVoucherWord.id.asc()).fetchFirst();
        }
        if (voucherWordId == null) {
            r.put("created", false);
            r.put("reason", "no_voucher_word");
            log.warn("[FN-SALARY-AUTOGEN-SELF-HEAL] 账套 {} 无 voucher_word，未建 {} 模板", accountSetsId, AUTO_GEN_TEMPLATE_TITLE);
            return r;
        }

        // 找/建主线科目（复用 initDefault 零件，autoCreatedSubjects 收集本次自动建的）
        List<Map<String, Object>> autoCreatedSubjects = new ArrayList<>();
        Long expenseSubjectId = findOrCreateSubject(as, "5602", "职工-工资", "管理费用_职工-工资", "损益", "借", autoCreatedSubjects);
        Long payableSubjectId = findOrCreateSubject(as, "2211", "职工-工资", "应付职工薪酬_职工-工资", "负债", "贷", autoCreatedSubjects);
        Long bankSubjectId = findFirstLeafByCodePrefix(accountSetsId, "1002");
        if (bankSubjectId == null) bankSubjectId = findFirstLeafByCodePrefix(accountSetsId, "1001");
        Long socialSubjectId = findOrCreateSubject(as, "2241", "代扣-社保", "其他应付款_代扣-社保", "负债", "贷", autoCreatedSubjects);
        Long taxSubjectId = findOrCreateSubject(as, "2241", "代扣-个税", "其他应付款_代扣-个税", "负债", "贷", autoCreatedSubjects);
        r.put("autoCreatedSubjects", autoCreatedSubjects);

        if (expenseSubjectId == null || payableSubjectId == null) {
            r.put("created", false);
            r.put("reason", "missing_core_subject_5602_2211");
            log.warn("[FN-SALARY-AUTOGEN-SELF-HEAL] 账套 {} 缺核心科目(5602/2211)，未建 {} 模板", accountSetsId, AUTO_GEN_TEMPLATE_TITLE);
            return r;
        }

        // 工资字段（多候选降级，找不到则字段绑空，amount 走 0，列入 unboundFields 待复核）
        Long fieldGross  = findSalaryFieldByCandidates(accountSetsId, FIELD_GROSS_CANDIDATES);
        Long fieldNet    = findSalaryFieldByCandidates(accountSetsId, FIELD_NET_CANDIDATES);
        Long fieldSocial = findSalaryFieldByCandidates(accountSetsId, FIELD_SOCIAL_CANDIDATES);
        Long fieldTax    = findSalaryFieldByCandidates(accountSetsId, FIELD_TAX_CANDIDATES);
        List<String> unbound = new ArrayList<>();
        if (fieldGross == null)  unbound.add("应发(gross)");
        if (fieldNet == null)    unbound.add("实发(net)");
        if (fieldSocial == null) unbound.add("代扣社保(social)");
        if (fieldTax == null)    unbound.add("代扣个税(tax)");
        r.put("unboundFields", unbound);

        // 合并模板：is_accrual=false、def=true（def=true 让 initDefault 不再建「…（默认）」双模板，杜绝命名分叉）
        SalaryVoucherTemplate t = new SalaryVoucherTemplate();
        t.setAccountSetsId(accountSetsId);
        t.setTitle(AUTO_GEN_TEMPLATE_TITLE);
        t.setIsAccrual(false);
        t.setDef(true);
        t.setVoucherWord(voucherWordId);
        List<SalaryVoucherTemplate.VoucherItems> items = new ArrayList<>();
        // 计提段：借 管理费用 / 贷 应付
        items.add(item(expenseSubjectId, "管理费用_职工-工资", "借", fieldGross, defaultName(FIELD_GROSS_CANDIDATES[0])));
        items.add(item(payableSubjectId, "应付职工薪酬_职工-工资", "贷", fieldGross, defaultName(FIELD_GROSS_CANDIDATES[0])));
        // 发放段：借 应付 / 贷 银行 + 代扣社保 + 代扣个税
        items.add(item(payableSubjectId, "应付职工薪酬_职工-工资", "借", fieldGross, defaultName(FIELD_GROSS_CANDIDATES[0])));
        if (bankSubjectId != null) {
            items.add(item(bankSubjectId, "银行存款", "贷", fieldNet, defaultName(FIELD_NET_CANDIDATES[0])));
        }
        if (socialSubjectId != null && fieldSocial != null) {
            items.add(item(socialSubjectId, "其他应付款_代扣-社保", "贷", fieldSocial, defaultName(FIELD_SOCIAL_CANDIDATES[0])));
        }
        if (taxSubjectId != null && fieldTax != null) {
            items.add(item(taxSubjectId, "其他应付款_代扣-个税", "贷", fieldTax, defaultName(FIELD_TAX_CANDIDATES[0])));
        }
        t.setItems(items);

        SalaryVoucherTemplate saved = testSaveSalaryVoucherTemplate(t);
        if (saved == null) {
            r.put("created", false);
            r.put("reason", "save_failed");
            return r;
        }
        r.put("created", true);
        r.put("templateId", saved.getId());
        log.info("[FN-SALARY-AUTOGEN-SELF-HEAL] 账套 {} 自动建 {} 模板 id={}（autoCreatedSubjects={} unboundFields={}）",
                accountSetsId, AUTO_GEN_TEMPLATE_TITLE, saved.getId(), autoCreatedSubjects.size(), unbound);
        return r;
    }

    /**
     * FN-SALARY-AUTOGEN-SELF-HEAL（配套查缺）：入参账套中「缺 title={@link #AUTO_GEN_TEMPLATE_TITLE} 模板」的账套 id。
     * <p>与 {@link #templateTitleExists} 同 {@code .eq} 口径（title collation utf8mb4_bin 精确匹配，一字不差）；
     * 等价 SQL {@code NOT EXISTS(salary_voucher_template WHERE title='计提发放工资')}。
     */
    public List<Long> findAccountsMissingAutoGenTemplate(List<Long> accountSetIds) {
        if (CollUtil.isEmpty(accountSetIds)) return new ArrayList<>();
        List<Long> have = bqf.selectFrom(qSalaryVoucherTemplate).select(qSalaryVoucherTemplate.accountSetsId).distinct()
                .where(qSalaryVoucherTemplate.accountSetsId.in(accountSetIds)
                        .and(qSalaryVoucherTemplate.title.eq(AUTO_GEN_TEMPLATE_TITLE)))
                .fetch();
        Set<Long> haveSet = new HashSet<>(have);
        List<Long> missing = new ArrayList<>();
        for (Long id : accountSetIds) {
            if (id != null && !haveSet.contains(id) && !missing.contains(id)) missing.add(id);
        }
        return missing;
    }

    /**
     * 检查并按需为该账套创建默认工资凭证模板（def=true）。
     * <p>幂等：已有任何 def=true 模板时直接返回 0；缺失主线科目/凭证字时跳过，不阻塞调用方。
     *
     * @return 本次新建的模板条数（通常 0 / 1 / 2）
     */
    @Transactional
    public int initDefaultTemplatesIfMissing(Long accountSetsId) {
        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(accountSetsId)).fetchFirst();
        if (accountSets == null) {
            log.warn("[FN-NEW-OPENAPI-SALARY-AUTOGEN] 账套不存在：{}", accountSetsId);
            return 0;
        }
        long existingDef = bqf.selectFrom(qSalaryVoucherTemplate)
                .where(qSalaryVoucherTemplate.accountSetsId.eq(accountSetsId)
                        .and(qSalaryVoucherTemplate.def.eq(true)))
                .fetchCount();
        if (existingDef > 0) return 0;

        // 凭证字（首选"记账凭证"，否则用该账套第一条）
        Long voucherWordId = bqf.selectFrom(qVoucherWord).select(qVoucherWord.id)
                .where(qVoucherWord.accountSetsId.eq(accountSetsId)
                        .and(qVoucherWord.printTitle.eq("记账凭证")))
                .fetchFirst();
        if (voucherWordId == null) {
            voucherWordId = bqf.selectFrom(qVoucherWord).select(qVoucherWord.id)
                    .where(qVoucherWord.accountSetsId.eq(accountSetsId))
                    .orderBy(qVoucherWord.id.asc())
                    .fetchFirst();
        }
        if (voucherWordId == null) {
            log.warn("[FN-NEW-OPENAPI-SALARY-AUTOGEN] 账套 {} 没有 voucher_word，跳过默认模板初始化", accountSetsId);
            return 0;
        }

        // 找/建主线科目
        Long expenseSubjectId = findOrCreateSubject(accountSets, "5602", "职工-工资",
                "管理费用_职工-工资", "损益", "借");
        Long payableSubjectId = findOrCreateSubject(accountSets, "2211", "职工-工资",
                "应付职工薪酬_职工-工资", "负债", "贷");
        Long bankSubjectId = findFirstLeafByCodePrefix(accountSetsId, "1002");
        if (bankSubjectId == null) bankSubjectId = findFirstLeafByCodePrefix(accountSetsId, "1001");
        Long socialSubjectId = findOrCreateSubject(accountSets, "2241", "代扣-社保",
                "其他应付款_代扣-社保", "负债", "贷");
        Long taxSubjectId = findOrCreateSubject(accountSets, "2241", "代扣-个税",
                "其他应付款_代扣-个税", "负债", "贷");

        if (expenseSubjectId == null || payableSubjectId == null) {
            log.warn("[FN-NEW-OPENAPI-SALARY-AUTOGEN] 账套 {} 缺核心科目（5602/2211 全无），跳过默认模板初始化", accountSetsId);
            return 0;
        }

        // 工资字段（找不到则字段绑空，amount 走 0 — data() 仍能算总额）
        Long fieldGross  = findSalaryFieldByCandidates(accountSetsId, FIELD_GROSS_CANDIDATES);
        Long fieldNet    = findSalaryFieldByCandidates(accountSetsId, FIELD_NET_CANDIDATES);
        Long fieldSocial = findSalaryFieldByCandidates(accountSetsId, FIELD_SOCIAL_CANDIDATES);
        Long fieldTax    = findSalaryFieldByCandidates(accountSetsId, FIELD_TAX_CANDIDATES);

        int created = 0;

        // 1) 计提工资 模板（计提应发）
        if (!templateTitleExists(accountSetsId, DEFAULT_ACCRUAL_TITLE)) {
            SalaryVoucherTemplate accrual = new SalaryVoucherTemplate();
            accrual.setAccountSetsId(accountSetsId);
            accrual.setTitle(DEFAULT_ACCRUAL_TITLE);
            accrual.setIsAccrual(true);
            accrual.setDef(true);
            accrual.setVoucherWord(voucherWordId);
            List<SalaryVoucherTemplate.VoucherItems> items = new ArrayList<>();
            items.add(item(expenseSubjectId, "管理费用_职工-工资", "借", fieldGross, defaultName(FIELD_GROSS_CANDIDATES[0])));
            items.add(item(payableSubjectId, "应付职工薪酬_职工-工资", "贷", fieldGross, defaultName(FIELD_GROSS_CANDIDATES[0])));
            accrual.setItems(items);
            if (testSaveSalaryVoucherTemplate(accrual) != null) created++;
        }

        // 2) 发放工资 模板（冲应付 + 实发出银行 + 代扣社保/个税）
        if (!templateTitleExists(accountSetsId, DEFAULT_PAYOUT_TITLE)) {
            SalaryVoucherTemplate payout = new SalaryVoucherTemplate();
            payout.setAccountSetsId(accountSetsId);
            payout.setTitle(DEFAULT_PAYOUT_TITLE);
            payout.setIsAccrual(false);
            payout.setDef(true);
            payout.setVoucherWord(voucherWordId);
            List<SalaryVoucherTemplate.VoucherItems> items = new ArrayList<>();
            items.add(item(payableSubjectId, "应付职工薪酬_职工-工资", "借", fieldGross, defaultName(FIELD_GROSS_CANDIDATES[0])));
            if (bankSubjectId != null) {
                items.add(item(bankSubjectId, "银行存款", "贷", fieldNet, defaultName(FIELD_NET_CANDIDATES[0])));
            }
            if (socialSubjectId != null && fieldSocial != null) {
                items.add(item(socialSubjectId, "其他应付款_代扣-社保", "贷", fieldSocial, defaultName(FIELD_SOCIAL_CANDIDATES[0])));
            }
            if (taxSubjectId != null && fieldTax != null) {
                items.add(item(taxSubjectId, "其他应付款_代扣-个税", "贷", fieldTax, defaultName(FIELD_TAX_CANDIDATES[0])));
            }
            payout.setItems(items);
            if (testSaveSalaryVoucherTemplate(payout) != null) created++;
        }

        log.info("[FN-NEW-OPENAPI-SALARY-AUTOGEN] 账套 {} 默认工资模板初始化完成，新建 {} 条", accountSetsId, created);
        return created;
    }

    private SalaryVoucherTemplate.VoucherItems item(Long subjectId, String subjectTitle, String direction,
                                                    Long salaryFieldId, String salaryFieldTitle) {
        SalaryVoucherTemplate.VoucherItems it = new SalaryVoucherTemplate.VoucherItems();
        it.setSubjectId(String.valueOf(subjectId));
        it.setSubjectTitle(subjectTitle);
        it.setDirection(direction);
        if (salaryFieldId != null) it.setSalaryFieldId(String.valueOf(salaryFieldId));
        it.setSalaryFieldTitle(salaryFieldTitle);
        return it;
    }

    private static String defaultName(String candidate) { return candidate; }

    private boolean templateTitleExists(Long accountSetsId, String title) {
        return bqf.selectFrom(qSalaryVoucherTemplate)
                .where(qSalaryVoucherTemplate.accountSetsId.eq(accountSetsId)
                        .and(qSalaryVoucherTemplate.title.eq(title)))
                .fetchCount() > 0;
    }

    /** 通过 fieldName 候选序列查找工资字段 ID（找不到返 null） */
    private Long findSalaryFieldByCandidates(Long accountSetsId, String[] candidates) {
        for (String name : candidates) {
            Long id = bqf.selectFrom(qSalaryField).select(qSalaryField.id)
                    .where(qSalaryField.accountSetsId.eq(accountSetsId)
                            .and(qSalaryField.fieldName.eq(name)))
                    .fetchFirst();
            if (id != null) return id;
        }
        return null;
    }

    /** 按 code 前缀（一级科目）找第一个末级子科目 ID */
    private Long findFirstLeafByCodePrefix(Long accountSetsId, String codePrefix) {
        Subject s = bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.code.startsWith(codePrefix))
                        .and(qSubject.leaf.eq(true))
                        .and(qSubject.status.eq(true)))
                .orderBy(qSubject.code.asc())
                .fetchFirst();
        return s != null ? s.getId() : null;
    }

    /**
     * 找/建子科目：先按 fullName 精确匹配 → 找不到按一级科目 parentCode 自动创建末级。
     * <p>account_sets 的 encoding 决定编码段长度——直接复用现有 nextAvailableChildCode + createSubject 模式。
     */
    private Long findOrCreateSubject(AccountSets accountSets, String parentCode, String childName,
                                     String fullName, String type, String balanceDirection) {
        return findOrCreateSubject(accountSets, parentCode, childName, fullName, type, balanceDirection, null);
    }

    /**
     * 同上；{@code autoCreatedOut} 非空时把<b>本次自动新建</b>的科目（subjectId/code/fullName）记入，
     * 供 ensureAutoGenTemplate 带出"待会计复核"清单（FN-SALARY-AUTOGEN-SELF-HEAL）。
     */
    private Long findOrCreateSubject(AccountSets accountSets, String parentCode, String childName,
                                     String fullName, String type, String balanceDirection,
                                     List<Map<String, Object>> autoCreatedOut) {
        Long accountSetsId = accountSets.getId();
        // 1) 精确匹配 fullName
        Long byFullName = bqf.selectFrom(qSubject).select(qSubject.id)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.fullName.eq(fullName))
                        .and(qSubject.status.eq(true)))
                .orderBy(qSubject.code.asc())
                .fetchFirst();
        if (byFullName != null) return byFullName;

        // 2) 找父科目 id
        Long parentId = bqf.selectFrom(qSubject).select(qSubject.id)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.code.eq(parentCode)))
                .fetchFirst();
        if (parentId == null) {
            // 父科目都没有 — 找该一级开头的任意末级（无奈兜底）
            return findFirstLeafByCodePrefix(accountSetsId, parentCode);
        }

        // 3) 自动建末级子科目（复用现有 createSubject + nextAvailableChildCode 模式）
        if (accountSets.getEncoding() == null || accountSets.getEncoding().size() < 2) {
            log.warn("[FN-NEW-OPENAPI-SALARY-AUTOGEN] 账套 {} encoding 不全，无法自动建科目 {}", accountSetsId, fullName);
            return findFirstLeafByCodePrefix(accountSetsId, parentCode);
        }
        int secondLen = accountSets.getEncoding().get(1);
        Integer encoding = accountSets.getEncoding().get(0)
                + accountSets.getEncoding().get(1)
                + accountSets.getEncoding().get(2)
                + accountSets.getEncoding().get(3);

        Subject base = new Subject();
        base.setAccountSetsId(accountSetsId);
        base.setStatus(true);
        base.setLevel(2);
        base.setCash(false);
        base.setSystemDefault(false);
        // FN-NEW-OPENAPI-ENSURE-SUBJECTS：自动补建的工资科目同样标「待确认」，与 ensure 行为一致
        base.setAutoCreated(true);
        String nextCode = nextAvailableChildCode(accountSetsId, parentCode, secondLen);
        if (nextCode == null) {
            return findFirstLeafByCodePrefix(accountSetsId, parentCode);
        }
        try {
            Subject created = createSubject(base, type, nextCode, childName, balanceDirection, parentId, fullName, encoding);
            if (created != null) {
                if (autoCreatedOut != null) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("subjectId", created.getId());
                    m.put("code", nextCode);
                    m.put("fullName", fullName);
                    autoCreatedOut.add(m);
                }
                return created.getId();
            }
            return findFirstLeafByCodePrefix(accountSetsId, parentCode);
        } catch (Exception e) {
            log.warn("[FN-NEW-OPENAPI-SALARY-AUTOGEN] 自动建科目失败：{} → {}", fullName, e.getMessage());
            return findFirstLeafByCodePrefix(accountSetsId, parentCode);
        }
    }
}
