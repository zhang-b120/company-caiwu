package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;

import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 辅助核算类别</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年10月22日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Entity
@DynamicInsert
@DynamicUpdate
@Comment("辅助分类")
public class AccountingCategory extends AbsEntity {

    @Comment("辅助类别")
    @Enumerated(EnumType.STRING)
    private AccountingCategoryType categoryType;

    /**
     * 辅助核算类别名称
     */
    @Comment("辅助核算类别")
    private String name;

    @Comment("辅助项列")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<AssistingAccountingColumn> columns;

    /**
     * 是否为系统默认
     */
    @Comment("系统默认")
    @ColumnDefault("0")
    private Boolean systemDefault;

    @Comment("是否可编辑")
    @ColumnDefault("1")
    private Boolean canEdit;

    /**
     * 辅助核算列
     */
    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AssistingAccountingColumn {
        private String code;
        private String title;
    }
}
