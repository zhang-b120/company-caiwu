package cn.gson.financial.kernel.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.annotation</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年12月05日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface DueCheck {
}
