package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.ReportItemsDto;
import cn.gson.financial.kernel.model.entity.ReportItems;
import org.mapstruct.*;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface ReportItemsMapper {
    ReportItems toEntity(ReportItemsDto reportItemsDto);

    ReportItemsDto toDto(ReportItems reportItems);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    ReportItems partialUpdate(ReportItemsDto reportItemsDto, @MappingTarget ReportItems reportItems);
}
