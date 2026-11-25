package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.*;

/**
 * @Author zhb
 * @Date 2025-09-16
 * @Description
 */
@Data
@Entity
@DynamicInsert
@DynamicUpdate
@Comment("Excel映射模板")
@Table(name = "excel_mapping_template")
public class ExcelMappingTemplate {

    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键")
    private Long id;

    @Comment("业务编码")
    @Column(name = "business_code", length = 50)
    private String businessCode;

    @Comment("业务类型 bankStatement-银行流水文件")
    @Column(name = "business_Type", length = 50)
    private String businessType;

    @Comment("映射字段Id")
    @Column(name = "mapping_field_id")
    private String mappingFieldId;

    @Comment("映射字段")
    @Column(name = "mapping_field", length = 50)
    private String mappingField;

    @Comment("映射类型：DIRECT-直接映射, COMBINE-组合映射, CONDITIONAL-条件映射（根据条件选择映射字段）, FIXED-固定值映射（不使用映射字段的值）")
    @Column(name = "mapping_type", length = 20)
    private String mappingType;

    @Comment("映射字段列表（JSON字符串）")
    @Column(name = "excel_fields", length = 255)
    private String excelFields;

    @Comment("判断字段列表（JSON字符串）")
    @Column(name = "judgment_fields", length = 255)
    private String judgmentFields;

    @Comment("判断条件配置（JSON字符串）")
    @Column(name = "conditions")
    private String conditions;

    @Comment("默认值")
    private String defaultValue;

    @Comment("是否启用 0否 1是")
    @Column(name = "enabled", length = 0)
    private Integer enabled;

    @Comment("是否删除 0否 1是")
    @Column(name = "is_delete")
    @ColumnDefault("0")
    private Integer isDelete;
}