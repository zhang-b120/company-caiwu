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

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 发票明细</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年12月19日</li>
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
@Comment("发票明细")
public class InvoiceItems extends AbsEntity {
    @Comment("发票ID")
    private Long invoiceId;
    @Comment("商品名称")
    private String goodsName;
    @Comment("商品型号")
    private String specification;
    @Comment("商品单位")
    private String unit;
    @Comment("商品数量")
    private Double quantity;
    @Comment("商品金额")
    private String amount;
    @Comment("税率")
    private Integer taxRate;
    @Comment("税")
    private BigDecimal tax;
    /**
     * 征税项目
     */
    @Comment("征税项目")
    private String taxedItems;
    /**
     * 计算方法
     */
    @Comment("计算方法")
    private String taxCalculationMethod;
}
