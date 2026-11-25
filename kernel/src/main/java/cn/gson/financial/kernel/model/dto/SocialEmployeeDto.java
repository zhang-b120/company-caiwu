package cn.gson.financial.kernel.model.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-10 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.dto</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-10</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@Getter
@Setter
public class SocialEmployeeDto {
    private Long id;
    private Long salarySecurityId;
    private Long fieldId;
    private Long companyFieldId;
    private Long personFieldId;
    private String salarySecurityName;
    private Long empId;
    private BigDecimal personRatio;
    private BigDecimal companyRatio;
    private BigDecimal depositBase;
    private BigDecimal fieldNum;
    private BigDecimal personNum;
    private BigDecimal companyNum;
    private String fieldName;
}
