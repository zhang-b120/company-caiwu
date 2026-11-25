package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.BalanceDto;
import cn.gson.financial.kernel.model.entity.Balance;
import cn.gson.financial.kernel.model.vo.BalanceKey;
import org.mapstruct.*;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface BalanceMapper {
    Balance toEntity(BalanceDto balanceDto);

    BalanceDto toDto(Balance balance);

    BalanceKey toKey(BalanceDto balance);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    Balance partialUpdate(BalanceDto balanceDto, @MappingTarget Balance balance);
}
