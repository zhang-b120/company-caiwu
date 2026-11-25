package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.SalaryFieldDto;
import cn.gson.financial.kernel.model.entity.SalaryField;
import org.mapstruct.*;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface SalaryFieldMapper {
    SalaryField toEntity(SalaryFieldDto salaryFieldDto);

    SalaryFieldDto toDto(SalaryField salaryField);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    SalaryField partialUpdate(SalaryFieldDto salaryFieldDto, @MappingTarget SalaryField salaryField);
}
