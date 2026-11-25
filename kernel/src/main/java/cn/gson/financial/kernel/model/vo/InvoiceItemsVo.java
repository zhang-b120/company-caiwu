package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.annotation.ColName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 
 * <p><b>Copyright © 2010-2024 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年02月05日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class InvoiceItemsVo {
    @ColName("发票代码")
    private String invoiceCode;
    @ColName("发票号码")
    private String invoiceNumber;
    @ColName("数电发票号码")
    private String netNumber;
    @ColName("货物或应税劳务名称")
    private String goodsName;
    @ColName("规格型号")
    private String specification;
    @ColName("单位")
    private String unit;
    @ColName("数量")
    private Double quantity;
    @ColName("单价")
    private BigDecimal price;
    @ColName("金额")
    private BigDecimal amount;
    @ColName("税率")
    private Integer taxRate;
    @ColName("税额")
    private BigDecimal tax;
    @ColName("备注")
    private String remark;
    @ColName("价税合计")
    private BigDecimal amountWithTax;
}
