package cn.gson.financial.kernel.config;

import cn.dev33.satoken.dao.SaTokenDaoRedisJackson;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import cn.gson.financial.kernel.converter.DateConverter;
import cn.gson.financial.kernel.converter.LocalDateConverter;
import cn.gson.financial.kernel.exception.ExperienceHasExpired;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.blazebit.persistence.ConfigurationProperties;
import com.blazebit.persistence.Criteria;
import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.spi.CriteriaBuilderConfiguration;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.flyemu</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年05月26日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Configuration
public class WebMvcSupport extends WebMvcConfigurationSupport {
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private SaTokenDaoRedisJackson saTokenDaoRedisJackson;
    @Resource
    private SessionHandlerMethodArgumentResolver sessionHandlerMethodArgumentResolver;
    @Resource
    private List<HandlerInterceptor> handlerInterceptors;
    @Resource
    private BuildProperties buildProperties;

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new DateConverter());
        registry.addConverter(new LocalDateConverter());
    }

    @Override
    protected void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
        argumentResolvers.add(sessionHandlerMethodArgumentResolver);
    }

    @Override
    protected void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
                    String allowHost = buildProperties.get("allowHost");
                    if (StrUtil.isNotEmpty(allowHost)) {
                        String host;
                        try {
                            host = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest().getHeader("Host");
                        } catch (Exception e) {
                            throw new ExperienceHasExpired("获取不到访问的有效域名信息，请检查环境~");
                        }

                        if (!host.endsWith(allowHost)) {
                            throw new ExperienceHasExpired(host + "未授权,请使用" + allowHost + "或其子域名访问~");
                        }
                    }

                    String expTime = buildProperties.get("expTime");
                    if (StrUtil.isNotEmpty(expTime)) {
                        LocalDate expDate = LocalDate.parse(expTime, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                        if (expDate.isAfter(LocalDate.now())) {
                            StpUtil.checkLogin();
                        } else {
                            throw new ExperienceHasExpired("服务已到期，请联系售前人员~");
                        }
                    } else {
                        StpUtil.checkLogin();
                    }
                }))
                .excludePathPatterns("/init", "/login", "/loginVerify", "/sendMsg/**", "/", "/regMsg/**", "/register", "/resetPassword", "/assets/**")
                .addPathPatterns("/**");
        handlerInterceptors.forEach(registry::addInterceptor);
    }

    @PostConstruct
    public void afterPropertiesSet() {
        SimpleModule simpleModule = new SimpleModule();
        simpleModule.addSerializer(LocalDate.class, new LocalDateSerializer(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        simpleModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        simpleModule.addSerializer(LocalTime.class, new LocalTimeSerializer(DateTimeFormatter.ofPattern("HH:mm:ss")));
        simpleModule.addDeserializer(Date.class, new JsonDeserializer<>() {
            @Override
            public Date deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return StrUtil.isNotEmpty(p.getText()) ? DateUtil.parse(p.getText(), "yyyy-MM-dd", "yyyy-MM", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm") : null;
            }
        });
        simpleModule.addDeserializer(LocalDateTime.class, new JsonDeserializer<>() {
            @Override
            public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                if (StrUtil.isNotEmpty(p.getText())) {
                    DateTime dateTime = DateUtil.parse(p.getText(), "yyyy-MM-dd", "yyyy-MM", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm");
                    return dateTime.toTimestamp().toLocalDateTime();
                }
                return null;
            }
        });
        simpleModule.addSerializer(Double.class, new JsonSerializer<>() {
            @Override
            public void serialize(Double value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                if (value != null) {
                    gen.writeNumber(NumberUtil.round(value, 2));
                }
            }
        });
        simpleModule.addSerializer(BigDecimal.class, new JsonSerializer<>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                if (value != null) {
                    gen.writeNumber(NumberUtil.round(value, 2));
                }
            }
        });
        simpleModule.addDeserializer(BigDecimal.class, new JsonDeserializer<>() {
            @Override
            public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JacksonException {
                if (StrUtil.isNotEmpty(p.getText())) {
                    try {
                        return NumberUtil.toBigDecimal(p.getText());
                    } catch (IOException e) {
                        return null;
                    }
                }
                return null;
            }
        });
        simpleModule.addSerializer(Long.class, new JsonSerializer<>() {
            @Override
            public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                if (value != null) {
                    gen.writeString(value.toString());
                }
            }
        });
        simpleModule.addDeserializer(LocalDate.class, new JsonDeserializer<>() {
            @Override
            public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                if (StrUtil.isNotEmpty(p.getText())) {
                    return LocalDateTimeUtil.of(DateUtil.parse(p.getText(), "yyyy-MM", "yyyy年MM期", "yyyy年MM月", "yyyy-MM-dd")).toLocalDate();
                }
                return null;
            }
        });

        objectMapper.registerModule(simpleModule);
        objectMapper.setDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
        // 禁止将时间类型数据转换成时间戳
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        saTokenDaoRedisJackson.objectMapper.registerModule(simpleModule);
        getMessageConverters().forEach(cv -> {
            if (cv instanceof MappingJackson2HttpMessageConverter) {
                ((MappingJackson2HttpMessageConverter) cv).setObjectMapper(objectMapper);
            }
        });
    }

    @Bean
    @Autowired
    public JPAQueryFactory jpaQuery(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    @Lazy(false)
    public CriteriaBuilderFactory createCriteriaBuilderFactory(EntityManagerFactory entityManagerFactory) {
        CriteriaBuilderConfiguration config = Criteria.getDefault();
        config.setProperty(ConfigurationProperties.INLINE_COUNT_QUERY, "false");
        config.setProperty(ConfigurationProperties.IMPLICIT_GROUP_BY_FROM_SELECT, "false");
        config.setProperty(ConfigurationProperties.IMPLICIT_GROUP_BY_FROM_HAVING, "false");
        config.setProperty(ConfigurationProperties.IMPLICIT_GROUP_BY_FROM_ORDER_BY, "false");
        return config.createCriteriaBuilderFactory(entityManagerFactory);
    }

    @Bean
    public HibernatePropertiesCustomizer jsonFormatMapperCustomizer() {
        return (properties) -> properties.put(AvailableSettings.JSON_FORMAT_MAPPER,
                new JacksonJsonFormatMapper(objectMapper));
    }
}
