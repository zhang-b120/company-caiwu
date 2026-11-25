package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.CheckoutTemplateDto;
import cn.gson.financial.kernel.model.entity.CheckoutTemplate;
import org.mapstruct.*;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface CheckoutTemplateMapper {
    CheckoutTemplate toEntity(CheckoutTemplateDto checkoutTemplateDto);

    CheckoutTemplateDto toDto(CheckoutTemplate checkoutTemplate);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    CheckoutTemplate partialUpdate(CheckoutTemplateDto checkoutTemplateDto, @MappingTarget CheckoutTemplate checkoutTemplate);
}
