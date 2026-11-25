package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.model.entity.Employee;
import cn.gson.financial.kernel.model.entity.SalarySocialEmployee;
import lombok.Data;

import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年12月27日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class SalaryEmpImportVo {

    private Employee employee;

    private List<SalarySocialEmployee> socialEmployees;

    public SalaryEmpImportVo(Employee employee) {
        this.employee = employee;
    }
}
