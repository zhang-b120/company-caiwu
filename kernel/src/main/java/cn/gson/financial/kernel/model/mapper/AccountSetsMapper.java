package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.AccountSetsDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface AccountSetsMapper {
    AccountSetsDto toDto(AccountSets accountSets);
}