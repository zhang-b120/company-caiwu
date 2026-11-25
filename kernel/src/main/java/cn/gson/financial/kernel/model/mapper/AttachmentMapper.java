package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.AttachmentDto;
import cn.gson.financial.kernel.model.entity.Attachment;
import org.mapstruct.*;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface AttachmentMapper {
    Attachment toEntity(AttachmentDto attachmentDto);

    @Mapping(target = "userName", expression = "java(username)")
    @Mapping(target = "type", expression = "java(attachment.getType().display)")
    AttachmentDto toDto(Attachment attachment, String username);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    Attachment partialUpdate(AttachmentDto attachmentDto, @MappingTarget Attachment attachment);
}
