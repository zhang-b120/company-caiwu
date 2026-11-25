package cn.gson.financial.kernel.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.TimeUnit;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 注解缓存失效时间</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年07月20日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheExpire {
    /**
     * 失效时间，默认60秒
     */
    public long ttl() default 60L;

    /**
     * 失效单位，默认秒
     */
    public TimeUnit unit() default TimeUnit.SECONDS;

    /**
     * 今天有效，失效时间是从当创建这一刻起，到晚上凌晨12点整，是一个动态的时间
     */
    public boolean today() default false;
}
