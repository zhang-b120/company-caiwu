package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.BalanceInitialDto;
import cn.gson.financial.kernel.model.entity.BalanceInitial;
import org.mapstruct.*;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface BalanceInitialMapper {
    BalanceInitial toEntity(BalanceInitialDto balanceInitialDto);

    BalanceInitialDto toDto(BalanceInitial balanceInitial);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    BalanceInitial partialUpdate(BalanceInitialDto balanceInitialDto, @MappingTarget BalanceInitial balanceInitial);
}
