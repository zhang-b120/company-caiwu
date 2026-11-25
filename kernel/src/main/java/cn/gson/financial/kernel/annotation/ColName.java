package cn.gson.financial.kernel.annotation;

import java.lang.annotation.*;

/**
 * 
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 导入映射的列名</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2021年08月15日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ColName {

    String value();
}
