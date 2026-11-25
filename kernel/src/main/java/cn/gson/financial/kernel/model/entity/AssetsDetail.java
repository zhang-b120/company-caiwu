package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年11月25日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@DynamicInsert
@DynamicUpdate
@Getter
@Setter
@Entity
@NoArgsConstructor
public class AssetsDetail extends AbsEntity {

    @Comment("资产ID")
    private Long assertId;

    @Comment("变更ID")
    private Long changeId;

    @Comment("折旧ID")
    private Long depreciationId;

    @Comment("凭证ID")
    private Long voucherId;

    @Comment("期间")
    private LocalDate checkDate;

    @Comment("摘要")
    private String digest;

    /**
     * 原值
     */
    @Comment("原值")
    private BigDecimal originalDebit;

    @Comment("借方原值")
    private BigDecimal originalCredit;

    @Comment("原余额")
    private BigDecimal originalBalance;

    /**
     * 折旧值
     */
    @Comment("折旧值")
    private BigDecimal depreciationDebit;

    @Comment("贷方折旧")
    private BigDecimal depreciationCredit;

    @Comment("折旧余额")
    private BigDecimal depreciationBalance;

    /**
     * 减值准备
     */
    @Comment("减值准备")
    private BigDecimal impairmentDebit;

    @Comment("贷方减值")
    private BigDecimal impairmentCredit;

    @Comment("减值余额")
    private BigDecimal impairmentBalance;

    @Comment("使用状态")
    private String useState;

    @Comment("部门")
    private String deptName;

    @Comment("部门Id")
    private Long deptId;

    /**
     * 净值
     */
    @Comment("净值")
    private BigDecimal netValue;

    public void setCheckDate(LocalDate checkDate) {
        this.checkDate = checkDate.withDayOfMonth(checkDate.getDayOfMonth());
    }
}
