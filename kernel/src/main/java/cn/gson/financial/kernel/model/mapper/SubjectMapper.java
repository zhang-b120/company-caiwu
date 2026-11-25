package cn.gson.financial.kernel.model.mapper;

import cn.gson.financial.kernel.model.dto.BalanceDto;
import cn.gson.financial.kernel.model.dto.SubjectDto;
import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.vo.SubjectVo;
import cn.hutool.core.lang.Dict;
import org.mapstruct.*;

import java.util.ArrayList;
import java.util.stream.Collectors;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface SubjectMapper {
    Subject toEntity(SubjectVo subjectVo);

    SubjectVo toVo(Subject subject);

    @Mappings({
            @Mapping(target = "subjectId", source = "id"),
            @Mapping(target = "subjectCode", source = "code"),
            @Mapping(target = "subjectName", source = "name"),
            @Mapping(target = "subjectFullName", source = "fullName"),
            @Mapping(target = "treeId", source = "id"),
            @Mapping(target = "parentTreeId", source = "parentId"),
            @Mapping(target = "id", ignore = true),
    })
    BalanceDto toBalanceDto(SubjectDto subject);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    Subject partialUpdate(SubjectVo subjectVo, @MappingTarget Subject subject);

    default SubjectMapper toAuxiliaryAccountingNames(SubjectVo subject, Dict aaMap) {
        if (subject != null && subject.getAuxiliaryAccounting() != null && aaMap != null) {
            subject.setAuxiliaryAccountingNames(subject.getAuxiliaryAccounting()
                    .stream().map(it -> aaMap.getStr(String.valueOf(it))).collect(Collectors.toList()));
        }else {
            subject.setAuxiliaryAccountingNames(new ArrayList<>());
        }
        return this;
    }

    default SubjectMapper toCurrencyAccountingNames(SubjectVo subject, Dict caMap) {
        if (subject != null && subject.getCurrencyAccounting() != null && caMap != null) {
            subject.setCurrencyAccountingNames(subject.getCurrencyAccounting()
                    .stream().map(it -> caMap.getStr(String.valueOf(it))).collect(Collectors.toList()));
        }else {
            subject.setCurrencyAccountingNames(new ArrayList<>());
        }
        return this;
    }
}
