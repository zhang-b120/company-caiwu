package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.SalaryDto;
import cn.gson.financial.kernel.model.entity.Salary;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING, uses = {SalaryFieldMapper.class})
public interface SalaryMapper {
    SalaryDto toDto(Salary salary);
}
