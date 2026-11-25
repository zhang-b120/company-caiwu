package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.CashierIoTypeVoucherTemplateDto;
import cn.gson.financial.kernel.model.entity.CashierIoTypeVoucherTemplate;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface CashierIoTypeVoucherTemplateMapper {
    CashierIoTypeVoucherTemplateDto toDto(CashierIoTypeVoucherTemplate cashierIoTypeVoucherTemplate);
}
