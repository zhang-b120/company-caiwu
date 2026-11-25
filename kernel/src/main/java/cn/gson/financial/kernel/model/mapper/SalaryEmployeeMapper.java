package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.SalaryEmployeeDto;
import cn.gson.financial.kernel.model.entity.Employee;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.mapper</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年11月06日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface SalaryEmployeeMapper {
    SalaryEmployeeDto toDto(Employee employee);
}
