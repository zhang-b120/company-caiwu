package cn.gson.financial.kernel.model.vo;

import lombok.Data;

/**
 * @Author zhb
 * @Date 2025-09-18
 * @Description
 */
@Data
public class SaveExcelMappingVo {

    private Long id;

    /**
     * 业务编码
     */
    private String businessCode;

    /**
     * 业务类型
     */
    private String businessType;

    /**
     * 映射字段Id
     */
    private String mappingFieldId;

    /**
     * 映射字段
     */
    private String mappingField;

    /**
     * 映射类型：DIRECT-直接映射, COMBINE-组合映射, CONDITIONAL-条件映射（根据条件选择映射字段）, FIXED-固定值映射（不使用映射字段的值）
     */
    private String mappingType;

    /**
     * 映射字段列表（JSON字符串）
     */
    private String excelFields;

    /**
     * 判断字段列表（JSON字符串）
     */
    private String judgmentFields;

    /**
     * 判断条件配置（JSON字符串）
     * 格式：[{"judgmentField":"A","judgmentType":"str","comparison":"contains","value":"str1","result":"A"}]
     * 判断方式comparison：contains-包含, equals-相等, gt-大于,ge-大于等于，lt-小于，le-小于等于）
     * 区别在在于值来源于 result可能为excel字段或固定值
     */
    private String conditions;

    /**
     * 默认值
     */
    private String defaultValue;

    /**
     * 是否启用 0否 1是
     */
    private Integer enabled;
}