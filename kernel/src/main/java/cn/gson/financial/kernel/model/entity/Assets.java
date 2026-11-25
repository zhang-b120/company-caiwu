package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.enums.AssetsDisposeType;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import cn.hutool.core.util.NumberUtil;
import jakarta.persistence.Table;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 固定资产</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年10月22日</li>
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
        @UniqueConstraint(columnNames = {"accountSetsId", "code"})
})
@NoArgsConstructor
@Comment("资产")
public class Assets extends AbsEntity {
    @Comment("编码")
    @Column(nullable = false, length = 32)
    private String code;

    @Column(nullable = false)
    @Comment("资产名称")
    private String name;

    @Comment("资产类别")
    private Long assetsTypeId;

    @Comment("购买日期")
    private LocalDate startDate;
    /**
     * 增加方式 {1:'购入',2:'在建工程转入',3:'其他'}
     */
    @Comment("增加方式")
    @ColumnDefault("1")
    private Long addType;

    @Comment("型号规格")
    private String specification;

    @Comment("供货商")
    private String supplier;

    @Comment("使用部门")
    private Long deptId;

    @Transient
    private transient String deptName;

    @Comment("录入期间")
    private LocalDate entryDate;

    @Comment("折旧方法")
    private String depreciationMethod;

    @Comment("资产科目")
    private String assetsSubjectCode;

    @Comment("累计折旧科目")
    private String totalDepreciationSubjectCode;

    @Comment("资产清理科目")
    private String assetsCleanSubjectCode;

    @Comment("折旧费用科目")
    private String depreciationExpenseSubjectCode;

    @Comment("资产原值")
    private BigDecimal originalValue;

    @Comment("已折旧月份")
    private Integer useMonth;

    @Comment("预计使用月份")
    private Integer expectUseMonth;

    @Comment("剩余使用月份")
    private Integer remainingUseMonth;

    @Comment("残值率")
    private BigDecimal residualRate;

    @Comment("预计残值")
    private BigDecimal expectResidualRate;

    @Comment("累计折旧")
    private BigDecimal totalDepreciation;

    @Comment("本年累计折旧")
    private BigDecimal yearTotalDepreciation;

    @Comment("以前年度累计折旧")
    private BigDecimal beforeYearTotalDepreciation;

    @Comment("每月折旧额")
    private BigDecimal currentMonthDepreciation;
    /**
     * {1:'正常',2:'停用',3:'出租'}
     */
    @Comment("状态")
    @ColumnDefault("1")
    private Integer status;

    @Comment("减少类型")
    private String reduceType;

    @Comment("状态")
    private LocalDate reduceDate;

    @Comment("备注")
    private String remark;

    @Comment("是否生成凭证")
    @ColumnDefault("0")
    private Boolean isVoucher;

    @Comment("周期")
    @ColumnDefault("0")
    private Boolean isPeriod;

    @CreationTimestamp
    private LocalDateTime createDateTime;

    /**
     * 处置类型
     */
    @Comment("处置类型")
    private AssetsDisposeType disposeType;
    /**
     * 处置期间
     */
    @Comment("处置期间")
    private LocalDate disposeCheckDate;

    public BigDecimal getTotalDepreciation() {
        return Optional.ofNullable(totalDepreciation).orElse(BigDecimal.ZERO);
    }

    public String getAddTypeVal() {
        if (this.addType == 1) {
            return "购入";
        } else if (this.addType == 2) {
            return "在建工程转入";
        }
        return "其他";
    }

    /**
     * 计算每月折旧
     * @param originalValue
     * @return
     */
    @Transient
    public BigDecimal currentMonthDepreciation(BigDecimal originalValue) {
        if (Objects.equals(this.expectUseMonth, this.useMonth)) {
            return BigDecimal.ZERO;
        }
        if (originalValue == null) {
            originalValue = this.originalValue;
        }
        // 残值率
        BigDecimal rate = NumberUtil.sub(1, NumberUtil.div(this.residualRate, 100d));
        // 除去残值的原值
        BigDecimal mulOriginalValue = NumberUtil.sub(NumberUtil.mul(originalValue, rate), totalDepreciation);
        // 剩余月份
        BigDecimal month = NumberUtil.sub(this.expectUseMonth, this.useMonth);
        return NumberUtil.div(mulOriginalValue, month);
    }
}

