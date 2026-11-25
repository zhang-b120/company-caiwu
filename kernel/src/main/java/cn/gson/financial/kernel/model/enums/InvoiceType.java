package cn.gson.financial.kernel.model.enums;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.enums</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年12月21日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public enum InvoiceType {
    OUT("开具发票"), IN("取得发票");

    public final String display;

    InvoiceType(String display) {
        this.display = display;
    }
}
