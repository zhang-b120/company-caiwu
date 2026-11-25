package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.enums.AccessRules;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 结转模板</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年04月18日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Entity
@DynamicInsert
@DynamicUpdate
@Comment("结账模板")
public class CheckoutTemplate extends AbsEntity {
    @Comment("模板名称")
    private String title;

    @Comment("凭证字")
    private Long voucherWordId;

    private Integer pos;

    @Comment("是否默认")
    @ColumnDefault("b'1'")
    @Column(nullable = false)
    private Boolean isDefault;

    @Comment("科目项")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<CtItem> items = new ArrayList<>();

    @Data
    public static class CtItem {
        /**
         * 摘要
         */
        private String summary;
        /**
         * 科目Id
         */
        private Long subjectId;
        /**
         * 摘要
         */
        private ValueType valueType;
        /**
         * 平衡比率
         */
        private BigDecimal eqRate;
        /**
         * 借贷方向
         */
        private String balanceDirection;
        /**
         * 金额
         */
        private BigDecimal amount;

        private List<RuleItem> rules = new ArrayList<>();

        public BigDecimal getAmount() {
            return Optional.ofNullable(amount).orElse(BigDecimal.ZERO);
        }
    }

    @Data
    public static class RuleItem {
        /**
         * 科目Id
         */
        private Long subjectId;
        /**
         * 运算符
         */
        private String operator;
        /**
         * 摘要
         */
        private AccessRules valueType;
        /**
         * 平衡比率
         */
        private BigDecimal rate;
    }

    public static enum ValueType {
        按公式取值, 自动平衡
    }
}
