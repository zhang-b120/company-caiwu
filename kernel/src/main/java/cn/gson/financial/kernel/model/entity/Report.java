package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.enums.ReportColDateLine;
import cn.gson.financial.kernel.model.enums.ReportDimension;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Table;
import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;

import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年09月05日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Entity
@DynamicUpdate
@DynamicInsert
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uc_report", columnNames = {"accountSetsId", "templateKey"})
})
@Comment("报表")
public class Report extends AbsEntity {

    @Comment("报表名称")
    private String name;

    @Comment("报表维度")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<ReportDimension> dimensions;

    @Comment("报表列")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<ReportCol> reportCols;

    @Comment("报表标识")
    private String templateKey;

    @Comment("是否默认")
    @ColumnDefault("b'1'")
    @Column(nullable = false)
    private Boolean isDefault;

    @Transient
    private List<ReportItems> items;

    @Comment("显示位置")
    @ColumnDefault("0")
    @Column(nullable = false)
    private Integer pos;

    @Data
    public static class ReportCol {
        //列名
        private String title;
        //取数时间截止线
        private ReportColDateLine dateLine;
    }
}
