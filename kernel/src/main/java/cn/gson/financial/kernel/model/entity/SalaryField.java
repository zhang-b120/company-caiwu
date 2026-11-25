package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.enums.DataType;
import cn.gson.financial.kernel.model.enums.FieldType;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Table;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-06 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-06</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@Getter
@Setter
@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uc_salaryfield_accountsetsid_code", columnNames = {"accountSetsId", "fieldName"})
})
@DynamicInsert
@DynamicUpdate
@Comment("薪资表字段")
public class SalaryField extends AbsEntity {

    @Comment("名称")
    private String fieldName;

    @Comment("社保字段ID")
    private Long socialFieldId;

    @Comment("取值：个人或企业")
    private String socialFieldType;

    /**
     * 是否启用 1 启用 0禁用
     */
    @Comment("状态")
    @Column(nullable = false)
    @ColumnDefault("b'1'")
    private Boolean enabled;

    @Column
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Comment("计算方案")
    private String formula;

    @Enumerated(EnumType.STRING)
    @Comment("字段类型")
    private FieldType fieldType;

    @Enumerated(EnumType.STRING)
    @Comment("数据类型")
    private DataType dataType;

    @Comment("权重")
    @Column(nullable = false)
    @ColumnDefault("1")
    private Integer weight;

    @Comment("是否可编辑")
    @Column(nullable = false)
    @ColumnDefault("b'1'")
    private Boolean edit;
}
