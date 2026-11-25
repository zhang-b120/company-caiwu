package cn.gson.financial.kernel.tool;

import cn.hutool.core.util.NumberUtil;

import java.math.BigDecimal;

/**
 * 
 * <p><b>Copyright © 2010-2024 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.tool</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年01月17日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public class NumUtil extends NumberUtil {

    public static BigDecimal div(BigDecimal val1, BigDecimal val2) {
        if (val1 == null || val2 == null || BigDecimal.ZERO.compareTo(val1) == 0 || BigDecimal.ZERO.compareTo(val2) == 0) {
            return BigDecimal.ZERO;
        }
        return NumberUtil.div(val1, val2);
    }
}
