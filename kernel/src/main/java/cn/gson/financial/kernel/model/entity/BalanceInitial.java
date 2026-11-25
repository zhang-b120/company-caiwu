package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import cn.hutool.core.util.ReflectUtil;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年06月07日</li>
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
        @UniqueConstraint(name = "uc_balance_initial_asac", columnNames = {"accountSetsId", "subjectId", "auxiliaryDetailId", "currencyId"})
})
@Comment("期初数据表")
public class BalanceInitial extends AbsEntity {
    /**
     * 科目
     */
    @Comment("科目")
    @Column(nullable = false)
    private Long subjectId;
    /**
     * 核算维度
     */
    @Comment("辅助核算维度")
    @ColumnDefault("0")
    @Column(nullable = false)
    private Long auxiliaryDetailId;

    /**
     * 币别
     */
    @Comment("币别")
    @ColumnDefault("0")
    @Column(nullable = false)
    private Long currencyId;

    /**
     * 期初本币金额
     */
    @Comment("期初本币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal beginBalance;
    /**
     * 期初原币金额
     */
    @Comment("期初原币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal beginBalanceFor;

    /**
     * 本年累计借方原币金额
     */
    @Comment("本年累计借方原币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal yearTotalDebitFor;
    /**
     * 本年累计借方本币金额
     */
    @Comment("本年累计借方本币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal yearTotalDebit;
    /**
     * 本年累计贷方原币金额
     */
    @Comment("本年累计贷方原币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal yearTotalCreditFor;
    /**
     * 本年累计贷方原币金额
     */
    @Comment("本年累计贷方本币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal yearTotalCredit;
    /**
     * 年初原币金额
     * 借方科目：年初数 = 期初数 + 本年累计贷方 - 本年累计借方
     * 贷方科目：年初数 = 期初数 + 本年累计借方 - 本年累计贷方
     */
    @Comment("年初原币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal yearTotalFor;
    /**
     * 年初本币金额
     * 借方科目：年初数 = 期初数 + 本年累计贷方 - 本年累计借方
     * 贷方科目：年初数 = 期初数 + 本年累计借方 - 本年累计贷方
     */
    @Comment("年初本币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal yearTotal;
    /**
     * 期初数量
     */
    @Comment("期初数量")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal beginQuantity;
    /**
     * 期初贷数量
     */
    @Comment("期初贷数量")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal creditQuantity;
    /**
     * 期初借数量
     */
    @Comment("期初借数量")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal debitQuantity;
    /**
     * 本年贷方累计数量
     */
    @Comment("本年贷方累计数量")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal yearTotalCreditQuantity;
    /**
     * 本年借方累计数量
     */
    @Comment("本年借方累计数量")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal yearTotalDebitQuantity;
    /**
     * 本年借方累计数量
     */
    @Comment("本年借方累计数量")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal yearTotalQuantity;

    public void setAuxiliaryDetailId(Long auxiliaryDetailId) {
        this.auxiliaryDetailId = Optional.ofNullable(auxiliaryDetailId).orElse(0L);
    }

    public boolean isNotEmpty() {
        Field[] fields = ReflectUtil.getFields(this.getClass(), s -> s.getType().equals(BigDecimal.class));
        return Arrays.stream(fields).anyMatch(f -> {
            BigDecimal fieldValue = (BigDecimal) ReflectUtil.getFieldValue(this, f);
            if (fieldValue == null) {
                return false;
            }
            return fieldValue.compareTo(BigDecimal.ZERO) != 0;
        });
    }
}
