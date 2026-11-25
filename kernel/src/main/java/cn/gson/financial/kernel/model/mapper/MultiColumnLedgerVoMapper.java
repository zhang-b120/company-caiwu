package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.entity.VoucherDetails;
import cn.gson.financial.kernel.model.vo.MultiColumnLedgerVo;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface MultiColumnLedgerVoMapper {
    MultiColumnLedgerVo toDto(VoucherDetails voucherDetails);
}