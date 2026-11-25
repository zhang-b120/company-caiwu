package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.entity.SalaryField;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

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
public class SalaryExportDetailDto {
    private List<SalaryField> salaryFields;
    private LocalDate period;
    private List<SalaryExportDetailFieldValDto> fieldVals;
}
