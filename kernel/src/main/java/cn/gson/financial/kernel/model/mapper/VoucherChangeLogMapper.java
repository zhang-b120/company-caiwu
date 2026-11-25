package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.VoucherChangeLogDto;
import cn.gson.financial.kernel.model.entity.VoucherChangeLog;
import org.mapstruct.*;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface VoucherChangeLogMapper {
    VoucherChangeLog toEntity(VoucherChangeLogDto voucherChangeLogDto);

    VoucherChangeLogDto toDto(VoucherChangeLog voucherChangeLog);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    VoucherChangeLog partialUpdate(VoucherChangeLogDto voucherChangeLogDto, @MappingTarget VoucherChangeLog voucherChangeLog);
}