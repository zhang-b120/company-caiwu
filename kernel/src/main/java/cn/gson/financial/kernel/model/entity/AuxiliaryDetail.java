package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Objects;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 辅助核算维度</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年04月06日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@DynamicInsert
@DynamicUpdate
@Getter
@Setter
@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uc_auxiliarydetail", columnNames = {"accountSetsId", "calCol"})
})
@NoArgsConstructor
@Comment("凭证辅助明细")
public class AuxiliaryDetail extends AbsEntity {

    @Comment("辅助项目组合值")
    @Column(columnDefinition = "json")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Detail> details;

    @Comment("辅助项目组合值")
    private String calCol;

    @Data
    public static class Detail {
        /**
         * 类型ID，表：AccountingCategory
         */
        private Long categoryId;

        private AccountingCategoryType categoryType;
        /**
         * 辅助项的值ID，表：AccountingCategoryDetails和部门、员工、客户、供应商、项目等表数据ID
         */
        private Long value;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Detail detail = (Detail) o;
            return Objects.equals(categoryId, detail.categoryId) && Objects.equals(value, detail.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(categoryId, value);
        }
    }
}
