package cn.gson.financial.kernel.config;

import cn.gson.financial.kernel.annotation.CacheExpire;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheOperationInvocationContext;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleCacheResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.CacheKeyPrefix;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.config</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年07月20日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory redisConnectionFactory, CacheProperties cacheProperties) {
        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(redisCacheConfiguration(cacheProperties, null))
                .build();
    }

    public RedisCacheConfiguration redisCacheConfiguration(CacheProperties cacheProperties, Long ttl) {
        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.WRAPPER_ARRAY);
        om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        om.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        //添加时间日期格式的处理
        JavaTimeModule javaTimeModule = new JavaTimeModule();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        DateTimeFormatter dtf2 = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        javaTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(dtf));
        javaTimeModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(dtf));
        javaTimeModule.addSerializer(LocalDate.class, new LocalDateSerializer(dtf2));
        javaTimeModule.addDeserializer(LocalDate.class, new LocalDateDeserializer(dtf2));

        SimpleModule simpleModule = new SimpleModule();
        simpleModule.addSerializer(Long.class, new JsonSerializer<>() {
            @Override
            public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                if (value != null) {
                    gen.writeString(value.toString());
                }
            }
        });
        om.registerModule(javaTimeModule);
        om.registerModule(simpleModule);

        //使用Jackson2JsonRedisSerializer的方式类序列化值
        Jackson2JsonRedisSerializer<Object> jackson2JsonRedisSerializer = new Jackson2JsonRedisSerializer<>(om, Object.class);

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jackson2JsonRedisSerializer));

        //获取配置的信息
        CacheProperties.Redis redisProperties = cacheProperties.getRedis();
        //设置过期时间
        if (null == ttl) {//传递的参数为空，则使用配置文件配置的过期时间
            if (null != redisProperties.getTimeToLive()) {
                config = config.entryTtl(redisProperties.getTimeToLive());
            }
        } else {
            config = config.entryTtl(Duration.ofHours(ttl));
        }

        //是否允许设置为null值
        if (!redisProperties.isCacheNullValues()) {
            config = config.disableCachingNullValues();
        }
        //是否设置前缀
        if (redisProperties.isUseKeyPrefix()) {
            //设置前缀
            if (null != redisProperties.getKeyPrefix()) {
                //添加上前缀，也可以自定义一个方式，默认是kerPrefix::的方式
                config = config.computePrefixWith(buildPrefix(redisProperties.getKeyPrefix()));  //自定义前缀
                //config = config.computePrefixWith(CacheKeyPrefix.prefixed(redisProperties.getKeyPrefix()));//默认的前缀方式
            } else { //不使用前缀
                config = config.computePrefixWith(buildBasePrefix());
            }
        } else {     //不使用前缀
            config = config.computePrefixWith(buildBasePrefix());
        }
        return config;
    }

    /**
     * 自定义前缀
     */
    private CacheKeyPrefix buildPrefix(String keyPrefix) {
        return (name) -> keyPrefix + name + ":";
    }

    //构造不适用前缀时的key格式
    private CacheKeyPrefix buildBasePrefix() {
        return cacheName -> {
            StringBuilder builder = new StringBuilder();
            builder.append(cacheName);
            return builder.toString();
        };
    }

    /**
     * 自定义缓存key生成方式
     */
    @Bean
    public KeyGenerator customKeyGenerator() {
        //根据target类、调用的方法method、传递的参数params组织spring cache的key
        return (target, method, params) -> target.getClass().getSimpleName() + "_" + method.getName() + "_" + StringUtils.arrayToDelimitedString(params, "_");
    }

    /**
     * 注册自定义的缓存处理类
     * cacheManager 为使用的缓存管理器
     * 使用CacheResolver来接收，它属于父接口
     * 使用方式，指明处理器: @Cacheable(cacheNames = "yyyy",key = "#root.args[0]",cacheResolver = "redisExpireCacheResolver")
     */
    @Bean
    public CacheResolver redisExpireCacheResolver(CacheManager cacheManager) {
        return new RedisExpireCacheResolver(cacheManager);
    }

    public static class RedisExpireCacheResolver extends SimpleCacheResolver {

        public RedisExpireCacheResolver(CacheManager cacheManager) {
            super(cacheManager);
        }

        //重写处理cache的方法
        @Override
        public Collection<? extends Cache> resolveCaches(CacheOperationInvocationContext<?> context) {
            //直接参考父父类AbstractCacheResolver的resolveCaches方法
            //获取当前注解中的缓存名，通过父类进行获取
            Collection<String> cacheNames = getCacheNames(context);
            if (cacheNames == null) {
                return Collections.EMPTY_LIST;
            } else {
                Collection<Cache> result = new ArrayList<>(cacheNames.size());
                for (String cacheName : cacheNames) {
                    //通过缓存名获取到缓存对象
                    Cache cache = this.getCacheManager().getCache(cacheName);
                    if (cache == null) {
                        throw new IllegalArgumentException("Cannot find cache named '" + cacheName + "' for " + context.getOperation());
                    }
                    //跟AbstractCacheResolver类的resolveCaches的方法比较，只有这里新增解析注解，反射替换RedisCacheConfiguration
                    this.parseCacheExpire(cache, context);

                    result.add(cache);
                }
                return result;
            }
        }

        /**
         * 通过反射替换cache中的RedisCacheConfiguration类已经设置好的过期时间等的值
         * 解析注解，从注解中获取新设置的过期时间，给RedisCacheConfiguration重新赋值
         */
        private void parseCacheExpire(Cache cache, CacheOperationInvocationContext<?> context) {
            Method method = context.getMethod();
            //判断方法是否包含过期时间注解
            if (method.isAnnotationPresent(CacheExpire.class)) {  //包含CacheExpire注解再处理
                //获取到注解
                CacheExpire cacheExpire = method.getAnnotation(CacheExpire.class);
                Duration duration = null;
                //判断注解是否配置的过期时间为今天
                if (cacheExpire.today()) {
                    //当配置了过期时间为今天，则计算从这一刻到凌晨12点还有多少时间
                    duration = Duration.ofSeconds(getSecondsNextEarlyMorning());
                } else {
                    //过期时间为用户自己配置，则根据配置的来创建Duration
                    duration = Duration.ofSeconds(cacheExpire.unit().toSeconds(cacheExpire.ttl()));
                }
                //转成RedisCache 这个时候cacheConfig是空的，也就让反射有了可乘之机
                RedisCache redisCache = (RedisCache) cache;
                //获取cache里面的RedisCacheConfiguration
                //新生成一个configuration
                RedisCacheConfiguration cacheConfig = redisCache.getCacheConfiguration();
                //参数需要对应修改
                cacheConfig = cacheConfig.entryTtl(duration);
                //通过反射获取到类型为RedisCacheConfiguration的字段cacheConfig
                Field field = ReflectionUtils.findField(RedisCache.class, "cacheConfig", RedisCacheConfiguration.class);
                //设置可以访问被private修饰的字段值
                field.setAccessible(true);
                //重新设置替换RedisCacheConfiguration
                ReflectionUtils.setField(field, redisCache, cacheConfig);
            }
        }

        //获取当前时间到第二天凌晨的秒数，用于设置redis失效时间为当天
        private Long getSecondsNextEarlyMorning() {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, 1);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.MILLISECOND, 0);
            return (cal.getTimeInMillis() - System.currentTimeMillis()) / 1000;
        }

    }
}
