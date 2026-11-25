package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.CashierIoTypeDto;
import cn.gson.financial.kernel.model.entity.CashierIoType;
import org.mapstruct.*;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface CashierIoTypeMapper {
    CashierIoType toEntity(CashierIoTypeDto cashierIoTypeDto);

    CashierIoTypeDto toDto(CashierIoType cashierIoType);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    CashierIoType partialUpdate(CashierIoTypeDto cashierIoTypeDto, @MappingTarget CashierIoType cashierIoType);
}
