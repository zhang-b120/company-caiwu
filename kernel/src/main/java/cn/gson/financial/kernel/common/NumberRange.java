package cn.gson.financial.kernel.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.common</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年04月06日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class NumberRange {
    private BigDecimal start;
    private BigDecimal end;

    public boolean isEmpty() {
        return start == null && end == null;
    }

    public boolean isNotEmpty() {
        return start != null && end != null;
    }
}
