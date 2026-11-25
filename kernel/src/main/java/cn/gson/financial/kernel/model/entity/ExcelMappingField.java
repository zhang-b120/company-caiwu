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
@Comment("Excel映射字段")
@Table(name = "excel_mapping_field")
public class ExcelMappingField {

    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键")
    private Long id;

    @Comment("映射字段")
    @Column(name = "mapping_field", length = 50)
    private String mappingField;

    @Comment("映射字段名称")
    @Column(name = "mapping_field_name", length = 50)
    private String mappingFieldName;

    @Comment("是否启用 0否 1是")
    @Column(name = "enabled", length = 0)
    private Integer enabled;

    @Comment("是否删除 0否 1是")
    @Column(name = "is_delete")
    @ColumnDefault("0")
    private Integer isDelete;
}