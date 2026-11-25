package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.bean.FieldValue;
import lombok.Data;

/**
 * 
 * <p><b>Copyright © 2010-2024 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.dto</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年01月04日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class SalaryExportDetailFieldValDto {
    private FieldValue fieldValue;
    private EmployeeDto employee;
}
