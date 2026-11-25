package cn.gson.financial.kernel.model.crm.req;

import cn.gson.financial.kernel.model.entity.Employee;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 对外添加员工接口请求体
 *
 * <p>员工档案走 {@code employee} 字段；可选 {@code positionSalary}（岗位工资），
 * 服务端按账套的 SalaryField「岗位工资」字段写入当期 SalaryFieldValue（不进入复核流程）。</p>
 *
 * <p>{@code birthDate} / {@code onboardingDate} 为顶层快捷字段：
 * <ul>
 *   <li>若 {@code employee.birthDate} 为空且本字段非空，则采用本字段；</li>
 *   <li>仍为空时服务端会从身份证号解析出生日期；</li>
 *   <li>{@code onboardingDate} 仍为空时服务端默认填当年 1 月 1 日。</li>
 * </ul>
 * 也可以直接在 {@code employee.birthDate} / {@code employee.onboardingDate} 里传，效果等价。</p>
 */
@Data
public class AddEmployeeReq {

    /** 员工档案 */
    private Employee employee;

    /**
     * 岗位工资（可选）。为空时不写入工资数据。
     */
    private BigDecimal positionSalary;

    /**
     * 出生日期（可选，顶层快捷字段）。为空时从身份证号解析。
     */
    private LocalDate birthDate;

    /**
     * 入职日期（可选，顶层快捷字段）。为空时默认当年 1 月 1 日。
     */
    private LocalDate onboardingDate;
}
