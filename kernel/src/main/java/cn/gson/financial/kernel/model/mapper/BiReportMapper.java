package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.BiReportDto;
import cn.gson.financial.kernel.model.entity.BiReport;
import org.mapstruct.*;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface BiReportMapper {
    BiReport toEntity(BiReportDto biReportDto);

    BiReportDto toDto(BiReport biReport);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    BiReport partialUpdate(BiReportDto biReportDto, @MappingTarget BiReport biReport);
}
