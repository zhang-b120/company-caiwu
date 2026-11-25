package cn.gson.financial.kernel.aspect;

import cn.gson.financial.kernel.tool.RedisUtil;
import cn.hutool.core.util.StrUtil;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.StandardReflectionParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Set;

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
@Aspect
@Component
public class CacheRemoveAspect {

    private final RedisUtil redisUtil;

    public CacheRemoveAspect(RedisUtil redisUtil) {
        this.redisUtil = redisUtil;
    }

    @Pointcut(value = "(execution(* *.*(..)) && @annotation(cn.gson.financial.kernel.aspect.CacheRemove))")
    private void pointcut() {
    }

    @AfterReturning(value = "pointcut()")
    private void process(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        CacheRemove cacheRemove = method.getAnnotation(CacheRemove.class);

        if (cacheRemove != null) {
            StandardEvaluationContext context = parseElContext(method, joinPoint.getArgs());
            String value = cacheRemove.value();
            String condition = cacheRemove.condition();
            String[] keys = cacheRemove.key(); //需要移除的正则key
            boolean checked = this.checkCondition(context, condition);
            if (checked) {
                for (String key : keys) {
                    String keyPrefix = this.getRedisKey(context, key);
                    Set<String> cacheKeys = redisUtil.keys(value + ":" + keyPrefix + "*");

                    for (String cacheKey : cacheKeys) {
                        redisUtil.delByKey(cacheKey);
                    }
                }
            }
        }
    }


    /**
     * 解析redisKey
     */
    private String getRedisKey(StandardEvaluationContext context, String key) {
        //使用SPEL进行key的解析
        ExpressionParser parser = new SpelExpressionParser();
        if (key.contains("#")) {
            return parser.parseExpression(key).getValue(context, String.class);
        } else {
            return key;
        }
    }


    /**
     * 解析条件
     */
    private boolean checkCondition(StandardEvaluationContext context, String condition) {
        ExpressionParser parser = new SpelExpressionParser();
        if (StrUtil.isEmpty(condition)) {
            return true;
        }
        return Boolean.TRUE.equals(parser.parseExpression(condition).getValue(context, Boolean.class));
    }

    /**
     * 内容解析
     */
    private StandardEvaluationContext parseElContext(Method method, Object[] args) {
        //获取被拦截方法参数名列表(使用Spring支持类库)
        StandardReflectionParameterNameDiscoverer localVariableTable = new StandardReflectionParameterNameDiscoverer();
        String[] paraNameArr = localVariableTable.getParameterNames(method);
        if (null != paraNameArr && paraNameArr.length > 0) {
            //SPEL上下文
            StandardEvaluationContext context = new StandardEvaluationContext();
            //把方法参数放入SPEL上下文中
            for (int i = 0; i < paraNameArr.length; i++) {
                context.setVariable(paraNameArr[i], args[i]);
            }
            return context;
        }
        return null;
    }
}
