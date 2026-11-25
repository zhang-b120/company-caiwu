package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.InvoiceBusinessTypeDto;
import cn.gson.financial.kernel.model.entity.InvoiceBusinessType;
import org.mapstruct.*;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface InvoiceBusinessTypeMapper {
    InvoiceBusinessType toEntity(InvoiceBusinessTypeDto invoiceBusinessTypeDto);

    InvoiceBusinessTypeDto toDto(InvoiceBusinessType invoiceBusinessType);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    InvoiceBusinessType partialUpdate(InvoiceBusinessTypeDto invoiceBusinessTypeDto, @MappingTarget InvoiceBusinessType invoiceBusinessType);
}
