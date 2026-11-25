package cn.gson.financial.task.workbench.service;

import cn.gson.financial.kernel.model.vo.SalaryDetailVo;
import cn.gson.financial.kernel.model.dto.SalaryEmployeeDto;
import cn.gson.financial.kernel.model.entity.QCheckout;
import cn.gson.financial.kernel.model.entity.QSalary;
import cn.gson.financial.kernel.model.entity.Salary;
import cn.gson.financial.kernel.service.SalaryService;
import cn.hutool.core.collection.CollUtil;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 任务工作台 - 填写工资内部任务
 * <p>
 * 逻辑：
 * 1. 无在职员工 → error
 * 2. 本月已有工资 → 跳过（done）
 * 3. 本月无工资，上月有工资 → 按员工ID匹配复制，匹配不到的置0
 * 4. 本月无工资，上月无工资 → 按在职员工新建，工资全部为0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskSalaryService {

    private final SalaryService salaryService;
    private final BlazeJPAQueryFactory bqf;

    private final QSalary qSalary = QSalary.salary;
    private final QCheckout qCheckout = QCheckout.checkout;
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * 为指定账套/账期执行工资填写任务。
     *
     * @param accountSetId 账套 ID
     * @param period       账期 yyyy-MM
     * @return 结果 map：status / msg / employee_count
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> execute(Long accountSetId, String period) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            YearMonth ym = YearMonth.parse(period, PERIOD_FMT);
            LocalDate targetPeriod = ym.atDay(1);
            LocalDate prevPeriod = ym.minusMonths(1).atDay(1);

            // 1. 获取当前在职员工列表
            SalaryDetailVo baseDetail = salaryService.detail(accountSetId, null);
            List<SalaryEmployeeDto> baseEmployees = baseDetail != null ? baseDetail.getSalaryEmployees() : null;
            if (CollUtil.isEmpty(baseEmployees)) {
                result.put("status", "error");
                result.put("msg", "无在职员工，跳过工资填写");
                return result;
            }

            // 2. 检查本月是否已有工资
            Salary existingSalary = bqf.selectFrom(qSalary)
                    .where(qSalary.accountSetsId.eq(accountSetId)
                            .and(qSalary.period.eq(targetPeriod)))
                    .fetchFirst();
            if (existingSalary != null) {
                if (!Boolean.TRUE.equals(existingSalary.getState())) {
                    if (isPeriodClosed(accountSetId, targetPeriod)) {
                        // 账期已结账，无法修改工资，但数据已存在，直接用于个税申报
                        result.put("status", "done");
                        result.put("msg", "当期工资已存在（账期已结账，跳过复核）");
                    } else {
                        salaryService.review(accountSetId, true, existingSalary.getId(), false);
                        result.put("status", "done");
                        result.put("msg", "当期工资已存在，已自动复核");
                    }
                } else {
                    result.put("status", "done");
                    result.put("msg", "当期工资已存在且已复核，跳过");
                }
                result.put("employee_count", baseEmployees.size());
                return result;
            }

            // 账期已结账且无工资记录：跳过工资准备，直接允许个税下发
            if (isPeriodClosed(accountSetId, targetPeriod)) {
                result.put("status", "done");
                result.put("msg", "账期已结账且无工资记录，跳过工资填写");
                return result;
            }

            // 3. 查找严格上月工资
            Salary prevSalary = bqf.selectFrom(qSalary)
                    .where(qSalary.accountSetsId.eq(accountSetId)
                            .and(qSalary.period.eq(prevPeriod)))
                    .fetchFirst();

            // 4. 构建本月工资员工列表（按员工ID匹配）
            SalaryDetailVo detailToSave = new SalaryDetailVo();
            String source;

            if (prevSalary != null) {
                // 上月有工资 → 按员工ID匹配，匹配不到的保持默认 FieldValue（值为0）
                SalaryDetailVo prevDetail = salaryService.detail(accountSetId, prevSalary.getId());
                Map<Long, SalaryEmployeeDto> prevEmpMap = prevDetail.getSalaryEmployees().stream()
                        .collect(Collectors.toMap(SalaryEmployeeDto::getId, e -> e));

                List<SalaryEmployeeDto> mergedEmployees = baseEmployees.stream().map(emp -> {
                    SalaryEmployeeDto prevEmp = prevEmpMap.get(emp.getId());
                    if (prevEmp != null) {
                        // 匹配到 → 复制上月工资数据
                        emp.setFieldValue(prevEmp.getFieldValue());
                    }
                    // 匹配不到 → 保持默认 new FieldValue()（工资为0）
                    return emp;
                }).collect(Collectors.toList());

                detailToSave.setSalaryFields(prevDetail.getSalaryFields());
                if (prevDetail.getSalary() != null) {
                    // 创建新 Salary 对象，避免修改 Hibernate managed entity 的 ID
                    Salary newSalary = new Salary();
                    newSalary.setPeriod(prevDetail.getSalary().getPeriod());
                    newSalary.setYear(prevDetail.getSalary().getYear());
                    newSalary.setMonth(prevDetail.getSalary().getMonth());
                    newSalary.setState(false);
                    newSalary.setSalaryFields(prevDetail.getSalary().getSalaryFields());
                    newSalary.setFieldValue(prevDetail.getSalary().getFieldValue());
                    detailToSave.setSalary(newSalary);
                }
                detailToSave.setSalaryEmployees(mergedEmployees);

                long matchedCount = mergedEmployees.stream()
                        .filter(e -> prevEmpMap.containsKey(e.getId())).count();
                source = "已复制上月工资（匹配 " + matchedCount + "/" + baseEmployees.size() + " 人，其余置0）";
            } else {
                // 上月无工资 → 全部员工工资置0
                detailToSave.setSalaryFields(baseDetail.getSalaryFields());
                detailToSave.setSalaryEmployees(baseEmployees);
                source = "上月无工资记录，已按在职员工新建（工资全部为0）";
            }

            // 5. 保存本月工资
            salaryService.save(accountSetId, targetPeriod, detailToSave, false);

            // 6. 自动复核
            Salary saved = bqf.selectFrom(qSalary)
                    .where(qSalary.accountSetsId.eq(accountSetId)
                            .and(qSalary.period.eq(targetPeriod)))
                    .fetchFirst();
            if (saved != null) {
                salaryService.review(accountSetId, true, saved.getId(), false);
            }

            result.put("status", "done");
            result.put("msg", source + "，员工数：" + baseEmployees.size() + "，已自动复核");
            result.put("employee_count", baseEmployees.size());
            log.info("工资填写完成: accountSetId={}, period={}, source={}", accountSetId, period, source);

        } catch (Exception e) {
            log.error("工资填写失败: accountSetId={}, period={}", accountSetId, period, e);
            result.put("status", "error");
            result.put("msg", "工资填写失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 检查账套是否有在职员工记录。
     * 个税申报前置校验使用。
     */
    public boolean hasEmployees(Long accountSetId) {
        SalaryDetailVo detail = salaryService.detail(accountSetId, null);
        return detail != null && !CollUtil.isEmpty(detail.getSalaryEmployees());
    }

    private boolean isPeriodClosed(Long accountSetId, LocalDate period) {
        Integer status = bqf.selectFrom(qCheckout)
                .select(qCheckout.status)
                .where(qCheckout.accountSetsId.eq(accountSetId)
                        .and(qCheckout.checkYear.eq(period.getYear()))
                        .and(qCheckout.checkMonth.eq(period.getMonthValue())))
                .fetchFirst();
        return status != null && status == 2;
    }
}
