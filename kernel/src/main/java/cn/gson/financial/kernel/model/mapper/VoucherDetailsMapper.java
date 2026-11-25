package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.VoucherDetailsDto;
import cn.gson.financial.kernel.model.entity.VoucherDetails;
import org.mapstruct.*;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface VoucherDetailsMapper {
    VoucherDetails toEntity(VoucherDetailsDto voucherDetailsDto);

    VoucherDetailsDto toDto(VoucherDetails voucherDetails);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    VoucherDetails partialUpdate(VoucherDetailsDto voucherDetailsDto, @MappingTarget VoucherDetails voucherDetails);
}
