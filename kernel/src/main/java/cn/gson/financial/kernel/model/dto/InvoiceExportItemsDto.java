package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.annotation.ColName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.dto</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年12月24日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class InvoiceExportItemsDto {
    @ColName(value = "发票种类")
    private String category;
    @ColName(value = "开票日期")
    private LocalDate tradeDate;
    @ColName(value = "发票代码")
    private String invoiceCode;
    @ColName(value = "发票号码")
    private String invoiceNumber;
    @ColName(value = "商品名称")
    private String goodsName;
    @ColName(value = "规格型号")
    private String specification;
    @ColName(value = "单位")
    private String unit;
    @ColName(value = "数量")
    private Double quantity;
    @ColName(value = "金额")
    private String amount;
    @ColName(value = "税率")
    private String taxRate;
    @ColName(value = "税额")
    private Double tax;
    @ColName(value = "征税项目")
    private String taxedItems;
    @ColName(value = "计税方法")
    private String taxCalculationMethod;

    public void setTaxRate(Integer taxRate) {
        this.taxRate = taxRate + "%";
    }
}
