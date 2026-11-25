package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.VoucherDto;
import cn.gson.financial.kernel.model.entity.Voucher;
import org.mapstruct.*;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface VoucherMapper {
    Voucher toEntity(VoucherDto voucherDto);

    VoucherDto toDto(Voucher voucher);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    Voucher partialUpdate(VoucherDto voucherDto, @MappingTarget Voucher voucher);
}
