package cn.gson.financial.kernel.model.enums;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.enums</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年11月26日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public enum AssetsDisposeType {
    SELL("出售"), SCRAP("报废"), OTHER("其他");

    public final String display;

    AssetsDisposeType(String display) {
        this.display = display;
    }
}
