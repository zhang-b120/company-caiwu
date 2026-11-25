package cn.gson.financial.kernel.model.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
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
public class SalarySocialEmployeeDto {

    private Long id;

    private Long accountSetsId;

    private Long socialId;

    private String name;

    private Long empId;

    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal personRatio;

    private BigDecimal personNum;

    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal companyRatio;

    private BigDecimal companyNum;

    private BigDecimal depositBase;
}
