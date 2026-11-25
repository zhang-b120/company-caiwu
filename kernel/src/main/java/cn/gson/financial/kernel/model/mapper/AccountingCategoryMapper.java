package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.AccountingCategoryDto;
import cn.gson.financial.kernel.model.entity.AccountingCategory;
import org.mapstruct.*;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface AccountingCategoryMapper {
    AccountingCategory toEntity(AccountingCategoryDto accountingCategoryDto);

    AccountingCategoryDto toDto(AccountingCategory accountingCategory);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    AccountingCategory partialUpdate(AccountingCategoryDto accountingCategoryDto, @MappingTarget AccountingCategory accountingCategory);
}
