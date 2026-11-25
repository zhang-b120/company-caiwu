package cn.gson.financial.kernel.model.dto;

import lombok.Data;

/**
 * @Author zhb
 * @Date 2025-09-16
 * @Description
 */
@Data
public class ExcelMappingFieldDto {

    private Long id;

    /**
     * 映射字段
     */
    private String mappingField;

    /**
     * 映射字段名称
     */
    private String mappingFieldName;

}