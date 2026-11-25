package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.model.dto.SalaryEmployeeDto;
import cn.gson.financial.kernel.model.entity.Salary;
import cn.gson.financial.kernel.model.entity.SalaryField;
import lombok.Data;

import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2024 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年01月04日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class SalaryDetailVo {
    private List<SalaryEmployeeDto> salaryEmployees;
    private List<SalaryField> salaryFields;
    private Salary salary;
}
