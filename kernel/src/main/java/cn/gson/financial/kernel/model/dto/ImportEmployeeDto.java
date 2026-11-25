package cn.gson.financial.kernel.model.dto;

import lombok.Data;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-26 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.dto</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-26</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@Data
public class ImportEmployeeDto {
    private Long id;
    private Long provisionId;
    private Long salaryScheduleId;
}