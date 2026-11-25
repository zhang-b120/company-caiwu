package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.enums.ReportItemType;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年09月18日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Entity
@DynamicUpdate
@DynamicInsert
@Comment("报表项")
public class ReportItems extends AbsEntity {

    @Comment("报表ID")
    private Long templateId;

    /**
     * 标题
     */
    @Comment("标题")
    private String title;

    @Comment("上级")
    private Long parentId;

    /**
     * 是否加粗标题
     */
    @Comment("是否加粗标题")
    @Column(nullable = false)
    @ColumnDefault("b'0'")
    private Boolean isBolder;

    /**
     * 是否可以折叠
     */
    @Comment("是否可以折叠")
    @Column(nullable = false)
    @ColumnDefault("b'0'")
    private Boolean isFolding;

    /**
     * 是否是归类项，归类项没有行号
     */
    @Comment("是否是归类项，归类项没有行号")
    @Column(nullable = false)
    @ColumnDefault("b'0'")
    private Boolean isClassified;

    /**
     * 显示位置
     */
    @Comment("显示位置")
    @Column(nullable = false)
    @ColumnDefault("0")
    private Integer pos;

    /**
     * 级别
     */
    @Comment("级别")
    @Column(nullable = false)
    @ColumnDefault("0")
    private Integer level;

    /**
     * 行号
     */
    @Comment("行号")
    @Column(length = 8)
    private String rowNum;

    /**
     * 是否允许编辑公式
     */
    @Comment("是否允许编辑公式")
    @Column(nullable = false)
    @ColumnDefault("b'1'")
    private Boolean editFormulas;

    @Comment("行类型")
    @JdbcTypeCode(SqlTypes.JSON)
    private Set<ReportItemType> itemType;

    @Comment("报表公式")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<ReportItemsFormula> formulas;

    @Comment("列期初数据")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, BigDecimal> colInitAmount;

    @Transient
    private List<ReportItems> children;

    public Set<ReportItemType> getItemType() {
        if (itemType == null) {
            Set<ReportItemType> types = new HashSet<>();
            types.add(ReportItemType.常规);
            types.add(ReportItemType.重分类);
            return types;
        }
        return itemType;
    }
}
