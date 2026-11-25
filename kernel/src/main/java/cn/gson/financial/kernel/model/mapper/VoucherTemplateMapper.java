package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.VoucherTemplateDto;
import cn.gson.financial.kernel.model.entity.VoucherTemplate;
import org.mapstruct.*;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface VoucherTemplateMapper {
    VoucherTemplate toEntity(VoucherTemplateDto voucherTemplateDto);

    VoucherTemplateDto toDto(VoucherTemplate voucherTemplate);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    VoucherTemplate partialUpdate(VoucherTemplateDto voucherTemplateDto, @MappingTarget VoucherTemplate voucherTemplate);
}
