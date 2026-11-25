package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.CurrencyDto;
import cn.gson.financial.kernel.model.entity.Currency;
import org.mapstruct.*;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface CurrencyMapper {
    Currency toEntity(CurrencyDto currencyDto);

    CurrencyDto toDto(Currency currency);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    Currency partialUpdate(CurrencyDto currencyDto, @MappingTarget Currency currency);
}
