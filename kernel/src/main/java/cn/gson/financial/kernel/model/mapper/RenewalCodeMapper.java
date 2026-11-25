package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.RenewalCodeDownloadDto;
import cn.gson.financial.kernel.model.dto.RenewalCodeDto;
import cn.gson.financial.kernel.model.entity.RenewalCode;
import org.mapstruct.*;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface RenewalCodeMapper {
    RenewalCode toEntity(RenewalCodeDto renewalCodeDto);

    RenewalCodeDto toDto(RenewalCode renewalCode);

    RenewalCodeDownloadDto toDownloadDto(RenewalCode renewalCode);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    RenewalCode partialUpdate(RenewalCodeDto renewalCodeDto, @MappingTarget RenewalCode renewalCode);
}
