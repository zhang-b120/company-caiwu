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
@Comment("Excel识别模板")
@Table(name = "excel_recognize_template")
public class ExcelRecognizeTemplate {

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

    @Comment("识别字段")
    @Column(name = "recognize_field", length = 50)
    private String recognizeField;

    @Comment("识别类型 header-数据表头 skip-数据跳过 end-数据结束")
    @Column(name = "recognize_type", length = 30)
    private String recognizeType;

    @Comment("是否启用 0否 1是")
    @Column(name = "enabled", length = 0)
    private Integer enabled;

    @Comment("是否删除 0否 1是")
    @Column(name = "is_delete")
    @ColumnDefault("0")
    private Integer isDelete;
}