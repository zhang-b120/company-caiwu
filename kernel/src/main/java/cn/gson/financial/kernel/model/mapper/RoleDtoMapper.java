package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.RoleDto;
import cn.gson.financial.kernel.model.entity.Role;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface RoleDtoMapper {
    Role toEntity(RoleDto roleDto);
}
