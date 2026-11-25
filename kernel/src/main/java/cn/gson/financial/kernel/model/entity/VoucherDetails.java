package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import cn.hutool.core.util.StrUtil;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年10月21日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Entity
@Table(indexes = {
        @Index(name = "idx_voucher_details_year_month", columnList = "accountSetsId, year, month")
})
@DynamicUpdate
@DynamicInsert
@Comment("凭证明细表")
public class VoucherDetails extends AbsEntity {
    @Comment("关联凭证")
    private Long voucherId;

    @Comment("期间年")
    private Integer year;

    @Comment("期间月")
    private Integer month;

    @Comment("凭证日期")
    private LocalDate voucherDate;

    @Comment("币别ID")
    private Long currencyId;

    @Comment("汇率")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19)
    private BigDecimal exchangeRate;

    @Comment("摘要")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String summary;

    @Comment("科目编码")
    private Long subjectId;

    @Comment("借方原币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19)
    private BigDecimal debitAmountFor;

    /**
     * 贷方金额
     */
    @Comment("贷方原币金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19)
    private BigDecimal creditAmountFor;

    @Comment("借方金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19)
    private BigDecimal debitAmount;

    /**
     * 贷方金额
     */
    @Comment("贷方金额")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19)
    private BigDecimal creditAmount;
    /**
     * 数量
     */
    @Comment("借方数量")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19)
    private BigDecimal debitNum;
    /**
     * 数量
     */
    @Comment("贷方数量")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19)
    private BigDecimal creditNum;
    /**
     * 数量
     */
    @Comment("数量")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19)
    private BigDecimal num;
    /**
     * 单价
     */
    @Comment("单价")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19)
    private BigDecimal price;
    /**
     * 核算维度
     */
    @Column(length = 64)
    @Comment("辅助核算维度")
    private Long auxiliaryDetailId;

    @Comment("是否是结转明细")
    @ColumnDefault("b'0'")
    @Column(nullable = false, updatable = false)
    private Boolean carryForward;

    @Comment("现金流")
    @ColumnDefault("b'0'")
    @Column(nullable = false)
    private Boolean cashFlow;

    public void setNum(BigDecimal num) {
        this.num = num;
        if (this.debitAmount != null && BigDecimal.ZERO.compareTo(this.debitAmount) != 0) {
            this.debitNum = num;
        } else {
            this.creditNum = num;
        }
    }

    public BigDecimal getNum() {
        return Optional.ofNullable(this.num).orElse(Optional.ofNullable(this.creditNum).orElse(this.debitNum));
    }

    public BigDecimal num() {
        return this.num;
    }

    /**
     * 获取金额
     *
     * @return
     */
    public BigDecimal getAmount() {
        if (this.debitAmount != null && BigDecimal.ZERO.compareTo(this.debitAmount) != 0) {
            return debitAmount;
        } else {
            return creditAmount;
        }
    }

    /**
     * 是否借方
     *
     * @return
     */
    public boolean isDebit() {
        return this.debitAmount != null && BigDecimal.ZERO.compareTo(this.debitAmount) != 0;
    }

    public void setSummary(String summary) {
        if (StrUtil.isNotEmpty(summary)) {
            this.summary = StrUtil.maxLength(summary, 220);
        } else {
            this.summary = "";
        }
    }
}
