package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.SalaryVoucherTemplateDto;
import cn.gson.financial.kernel.model.entity.SalaryVoucherTemplate;
import org.mapstruct.*;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-11 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.mapper</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-11</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface SalaryVoucherTemplateMapper {
    SalaryVoucherTemplate toEntity(SalaryVoucherTemplateDto salaryVoucherTemplateDto);

    SalaryVoucherTemplateDto toDto(SalaryVoucherTemplate salaryVoucherTemplate);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    SalaryVoucherTemplate partialUpdate(SalaryVoucherTemplateDto salaryVoucherTemplateDto, @MappingTarget SalaryVoucherTemplate salaryVoucherTemplate);

}