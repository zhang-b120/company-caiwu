package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.model.dto.SalarySocialEmployeeDto;
import cn.gson.financial.kernel.model.entity.Employee;
import lombok.Data;

import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2024 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年01月09日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class EmployeeFormVo {
    private Employee employee;
    private List<SalarySocialEmployeeDto> socials;
}
