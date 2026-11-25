package cn.gson.financial.kernel.aspect;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.aspect</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年04月28日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Target({java.lang.annotation.ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheRemove {
    String value();

    String[] key();

    String condition() default "";
}
