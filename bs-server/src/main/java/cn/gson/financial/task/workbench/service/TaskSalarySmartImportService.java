package cn.gson.financial.task.workbench.service;

import cn.gson.financial.kernel.model.bean.FieldValue;
import cn.gson.financial.kernel.model.bean.Value;
import cn.gson.financial.kernel.model.dto.SalaryEmployeeDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.vo.SalaryDetailVo;
import cn.gson.financial.kernel.service.EmployeeService;
import cn.gson.financial.kernel.service.SalaryService;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 任务工作台 - 工资智能识别导入
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskSalarySmartImportService {

    private final EmployeeService employeeService;
    private final SalaryService salaryService;
    private final BlazeJPAQueryFactory bqf;

    private final QEmployee qEmployee = QEmployee.employee;
    private final QSalaryField qSalaryField = QSalaryField.salaryField;
    private final QSalary qSalary = QSalary.salary;

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * 智能导入：创建/更新员工 + 写入工资 + 复核
     *
     * @param accountSetId 账套ID
     * @param period       账期 yyyy-MM
     * @param rows         解析后的员工数据，每条含 name / phone / idCard / salary
     */
    @Transactional
    public Map<String, Object> smartImport(Long accountSetId, String period, List<Map<String, Object>> rows) {
        YearMonth ym = YearMonth.parse(period, PERIOD_FMT);
        LocalDate targetPeriod = ym.atDay(1);

        // 1. 先批量找或创建员工，保证他们在在职名单里
        for (Map<String, Object> row : rows) {
            findOrCreateEmployee(accountSetId,
                    str(row.get("name")), str(row.get("phone")), str(row.get("idCard")),
                    str(row.get("birthDate")), str(row.get("onboardingDate")));
        }

        // 2. 找工资字段：优先"岗位工资"，兜底"基本工资"
        SalaryField basicField = bqf.selectFrom(qSalaryField)
                .where(qSalaryField.accountSetsId.eq(accountSetId)
                        .and(qSalaryField.fieldName.eq("岗位工资"))
                        .and(qSalaryField.enabled.isTrue()))
                .fetchFirst();
        if (basicField == null) {
            basicField = bqf.selectFrom(qSalaryField)
                    .where(qSalaryField.accountSetsId.eq(accountSetId)
                            .and(qSalaryField.fieldName.eq("基本工资"))
                            .and(qSalaryField.enabled.isTrue()))
                    .fetchFirst();
        }

        // 3. 获取所有在职员工的模板数据（含字段定义）
        SalaryDetailVo detail = salaryService.detail(accountSetId, null);
        if (detail == null || CollUtil.isEmpty(detail.getSalaryEmployees())) {
            throw new IllegalStateException("无在职员工，无法导入工资");
        }

        // 4. 建立导入数据的索引（idCard 优先，姓名兜底）
        Map<String, BigDecimal> salaryByIdCard = new HashMap<>();
        Map<String, BigDecimal> salaryByName = new HashMap<>();
        for (Map<String, Object> row : rows) {
            BigDecimal amt = parseBD(str(row.get("salary")));
            String idCard = StrUtil.trimToEmpty(str(row.get("idCard")));
            String name = StrUtil.trimToEmpty(str(row.get("name")));
            if (StrUtil.isNotBlank(idCard)) salaryByIdCard.put(idCard, amt);
            if (StrUtil.isNotBlank(name)) salaryByName.put(name, amt);
        }

        // 5. 给匹配的员工设置基本工资
        int matched = 0;
        if (basicField != null) {
            for (SalaryEmployeeDto emp : detail.getSalaryEmployees()) {
                BigDecimal amt = null;
                if (StrUtil.isNotBlank(emp.getIdCard()) && salaryByIdCard.containsKey(emp.getIdCard().trim())) {
                    amt = salaryByIdCard.get(emp.getIdCard().trim());
                } else if (StrUtil.isNotBlank(emp.getName()) && salaryByName.containsKey(emp.getName().trim())) {
                    amt = salaryByName.get(emp.getName().trim());
                }
                if (amt != null) {
                    FieldValue fv = emp.getFieldValue() != null ? emp.getFieldValue() : new FieldValue();
                    fv.put(basicField.getId().toString(), new Value(basicField.getFieldName(), amt));
                    emp.setFieldValue(fv);
                    matched++;
                }
            }
        }

        // 6. 若本期已有工资且已复核，先撤销复核
        Salary existing = bqf.selectFrom(qSalary)
                .where(qSalary.accountSetsId.eq(accountSetId).and(qSalary.period.eq(targetPeriod)))
                .fetchFirst();
        if (existing != null) {
            if (Boolean.TRUE.equals(existing.getState())) {
                salaryService.review(accountSetId, false, existing.getId(), false);
            }
            detail.setSalary(existing);
        }

        // 7. 保存
        salaryService.save(accountSetId, targetPeriod, detail, false);

        // 8. 复核
        Salary saved = bqf.selectFrom(qSalary)
                .where(qSalary.accountSetsId.eq(accountSetId).and(qSalary.period.eq(targetPeriod)))
                .fetchFirst();
        if (saved != null) {
            salaryService.review(accountSetId, true, saved.getId(), false);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "done");
        result.put("importCount", rows.size());
        result.put("matchedCount", matched);
        result.put("employeeCount", detail.getSalaryEmployees().size());
        result.put("msg", "已导入 " + rows.size() + " 人，匹配工资 " + matched + " 人，共 "
                + detail.getSalaryEmployees().size() + " 名员工，已自动复核");
        log.info("工资智能导入完成: accountSetId={}, period={}, import={}, matched={}",
                accountSetId, period, rows.size(), matched);
        return result;
    }

    // ── 内部工具 ────────────────────────────────────────────────────────────

    private void findOrCreateEmployee(Long accountSetId, String name, String phone, String idCard,
                                       String birthDate, String onboardingDate) {
        // 按身份证查找
        if (StrUtil.isNotBlank(idCard)) {
            Employee existing = bqf.selectFrom(qEmployee)
                    .where(qEmployee.accountSetsId.eq(accountSetId)
                            .and(qEmployee.idCard.eq(idCard.trim())))
                    .fetchFirst();
            if (existing != null) {
                return;
            }
        }
        // 按姓名查找（无身份证时）
        if (StrUtil.isBlank(idCard) && StrUtil.isNotBlank(name)) {
            Employee existing = bqf.selectFrom(qEmployee)
                    .where(qEmployee.accountSetsId.eq(accountSetId)
                            .and(qEmployee.name.eq(name.trim())))
                    .fetchFirst();
            if (existing != null) {
                return;
            }
        }
        // 创建新员工
        Employee emp = new Employee();
        emp.setAccountSetsId(accountSetId);
        emp.setName(StrUtil.trimToEmpty(name));
        emp.setTelPhone(StrUtil.trimToEmpty(phone));
        emp.setIdCard(StrUtil.trimToEmpty(idCard));
        emp.setCategoryType(AccountingCategoryType.EMPLOYEE);
        emp.setCategoryId((long) AccountingCategoryType.EMPLOYEE.ordinal());
        emp.setEnable(true);
        emp.setStatus("在职");
        emp.setPurchase(false);
        emp.setBirthDate(parseDate(birthDate));
        emp.setOnboardingDate(StrUtil.isNotBlank(onboardingDate)
                ? parseDate(onboardingDate)
                : LocalDate.of(LocalDate.now().getYear(), 1, 1));
        emp.setCode(employeeService.getEmployeeCode(accountSetId));
        employeeService.save(emp);
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString().trim();
    }

    private static LocalDate parseDate(String s) {
        try {
            return StrUtil.isNotBlank(s) ? LocalDate.parse(s.trim()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal parseBD(String s) {
        try {
            return StrUtil.isNotBlank(s) ? new BigDecimal(s.trim()) : BigDecimal.ZERO;
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
}
