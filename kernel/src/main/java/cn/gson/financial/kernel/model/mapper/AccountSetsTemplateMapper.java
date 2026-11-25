package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.AccountSetsTemplateDto;
import cn.gson.financial.kernel.model.entity.AccountSetsTemplate;
import org.mapstruct.*;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface AccountSetsTemplateMapper {
    AccountSetsTemplate toEntity(AccountSetsTemplateDto accountSetsTemplateDto);

    @Mapping(target = "subjectCategoryDisplay", expression = "java(accountSetsTemplate.getSubjectCategory().stream().map(it -> it.display).collect(java.util.stream.Collectors.toList()))")
    AccountSetsTemplateDto toDto(AccountSetsTemplate accountSetsTemplate);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    AccountSetsTemplate partialUpdate(AccountSetsTemplateDto accountSetsTemplateDto, @MappingTarget AccountSetsTemplate accountSetsTemplate);
}
