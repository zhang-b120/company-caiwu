package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.UserDto;
import cn.gson.financial.kernel.model.entity.User;
import cn.gson.financial.kernel.model.vo.UserVo;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)public interface UserMapper {
    UserVo toVo(User user);
    UserDto toDto(User user);
}
