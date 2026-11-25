package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.ReflectUtil;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 余额表</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年02月20日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Entity
@DynamicUpdate
@DynamicInsert
@Table(indexes = {
        @Index(name = "idx_balance_accountsets_year_period", columnList = "accountSetsId, year, period"),
        @Index(name = "idx_balance_accountsets_date", columnList = "accountSetsId, date")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uc_balance_asac", columnNames = {"accountSetsId", "subjectId", "auxiliaryDetailId", "currencyId", "year", "period"})
})
@Comment("余额表")
public class Balance extends AbsEntity {
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
     * 期间年月
     */
    @Comment("期间年月")
    @Column(nullable = false)
    private LocalDate date;
    /**
     * 年
     */
    @Comment("年")
    @Column(nullable = false)
    private Integer year;
    /**
     * 期间
     */
    @Comment("期间")
    @Column(nullable = false)
    private Integer period;
    /**
     * 调整期,0表示正常期间，>0表示调整期间
     */
    @Comment("调整期")
    @ColumnDefault("0")
    @Column(nullable = false)
    private Integer adjustPeriod;
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
     * 期末本币金额
     */
    @Comment("期末本币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal endBalance;
    /**
     * 期末原币金额
     */
    @Comment("期末原币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal endBalanceFor;
    /**
     * 期初借方本币金额
     */
    @Comment("期初借方本币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal beginDebitBalance;
    /**
     * 期初借方原币金额
     */
    @Comment("期初借方原币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal beginDebitBalanceFor;
    /**
     * 期末借方本币金额
     */
    @Comment("期末借方本币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal endDebitBalance;
    /**
     * 期末借方原币金额
     */
    @Comment("期末借方原币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal endDebitBalanceFor;
    /**
     * 期初贷方本币金额
     */
    @Comment("期初贷方本币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal beginCreditBalance;
    /**
     * 期初贷方原币金额
     */
    @Comment("期初贷方原币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal beginCreditBalanceFor;
    /**
     * 期末贷方本币金额
     */
    @Comment("期末贷方本币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal endCreditBalance;
    /**
     * 期末贷方原币金额
     */
    @Comment("期末贷方原币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal endCreditBalanceFor;
    /**
     * 借方原币金额
     */
    @Comment("借方原币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal debitFor;
    /**
     * 借方本币金额
     */
    @Comment("借方本币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal debit;
    /**
     * 贷方原币金额
     */
    @Comment("贷方原币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal creditFor;
    /**
     * 贷方本币金额
     */
    @Comment("贷方本币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal credit;
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

    @Comment("年初借方本币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal yearTotalBeginDebit;

    @Comment("年初贷方本币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal yearTotalBeginCredit;

    @Comment("年初借方外币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal yearTotalBeginDebitFor;

    @Comment("年初贷方外币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal yearTotalBeginCreditFor;
    /**
     * 期初数量
     */
    @Comment("期初数量")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal beginQuantity;
    /**
     * 期末数量
     */
    @Comment("期末数量")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal endQuantity;
    /**
     * 本期贷方累计数量
     */
    @Comment("本期贷方累计数量")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal creditQuantity;
    /**
     * 本期借方累计数量
     */
    @Comment("本期借方累计数量")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal debitQuantity;
    /**
     * 本期贷方累计数量
     */
    @Comment("本期期末贷方累计数量")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal endCreditQuantity;
    /**
     * 本期借方累计数量
     */
    @Comment("本期期末借方累计数量")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19, nullable = false)
    private BigDecimal endDebitQuantity;
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
     * 年初累计数量
     */
    @Comment("年初累计数量")
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

    public void init() {
        Field[] fields = ReflectUtil.getFields(this.getClass(), s -> s.getType().equals(BigDecimal.class));
        Arrays.stream(fields).forEach(f -> ReflectUtil.setFieldValue(this, f, BigDecimal.ZERO));
    }

    /**
     * 合计本年累计
     */
    public void calculateYearTotal() {
        this.yearTotalCredit = NumberUtil.add(this.yearTotalCredit, this.credit);
        this.yearTotalCreditFor = NumberUtil.add(this.yearTotalCredit, this.creditFor);
        this.yearTotalDebit = NumberUtil.add(this.yearTotalDebit, this.debit);
        this.yearTotalDebitFor = NumberUtil.add(this.yearTotalDebit, this.debitFor);
        this.yearTotalCreditQuantity = NumberUtil.add(this.yearTotalCreditQuantity, this.creditQuantity);
        this.yearTotalDebitQuantity = NumberUtil.add(this.yearTotalDebitQuantity, this.debitQuantity);
    }
}
