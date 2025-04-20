package com.ispengya.sharding.sharding;

import com.ispengya.sharding.DBRouter;
import com.ispengya.sharding.DBShardingStrategy;
import com.ispengya.sharding.config.ShardingProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * @description:
 * @author: hanzhipeng
 * @create: 2025-04-20 14:15
 **/
@Slf4j
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class ShardingDBAspect {

    private final DBShardingRule dbShardingRule;
    private final Map<String, DBShardingStrategy> shardingStrategyMap;

    public ShardingDBAspect(ShardingRuleRegistry shardingRuleRegistry) {
        this.dbShardingRule = shardingRuleRegistry.getDbShardingRule();
        this.shardingStrategyMap = shardingRuleRegistry.getDbStrategyMap();
    }

    @Pointcut("@annotation(com.ispengya.sharding.DBRouter)")
    public void aopPoint() {
    }

    @Around("aopPoint() && @annotation(dbRouter)")
    public Object doRouter(ProceedingJoinPoint jp, DBRouter dbRouter) throws Throwable {
        String dbKeyAttr = getAttrValue(dbShardingRule.getShardingKey(), jp.getArgs());
        DBShardingStrategy dbShardingStrategy = shardingStrategyMap.get(dbShardingRule.getStrategyKey());
        String dbRouteKey = dbShardingStrategy.shard(dbShardingRule, dbKeyAttr);
        ShardingContextHolder.setDbHolder(dbRouteKey);
        try {
            return jp.proceed();
        } finally {
            ShardingContextHolder.clearDbHolder();
        }
    }

    private Method getMethod(JoinPoint jp) throws NoSuchMethodException {
        Signature sig = jp.getSignature();
        MethodSignature methodSignature = (MethodSignature) sig;
        return jp.getTarget().getClass().getMethod(methodSignature.getName(), methodSignature.getParameterTypes());
    }

    /**
     * 根据字段名获取参数对象指定的字段数据
     *
     * @param attr 字段名
     * @param args 参数列表
     * @return 对象指定字段的数据
     */
    public String getAttrValue(String attr, Object[] args) {
        if (1 == args.length) {
            Object arg = args[0];
            if (arg instanceof String) {
                return arg.toString();
            }
        }

        String filedValue = null;
        for (Object arg : args) {
            try {
                if (StringUtils.isNotBlank(filedValue)) {
                    break;
                }
                // filedValue = BeanUtils.getProperty(arg, attr);
                // fix: 使用lombok时，uId这种字段的get方法与idea生成的get方法不同，会导致获取不到属性值，改成反射获取解决
                filedValue = String.valueOf(this.getValueByName(arg, attr));
            } catch (Exception e) {
                log.error("获取路由属性值失败 attr：{}", attr, e);
            }
        }
        return filedValue;
    }

    private Object getValueByName(Object item, String name) {
        try {
            Field field = getFieldByName(item, name);
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            Object o = field.get(item);
            field.setAccessible(false);
            return o;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private Field getFieldByName(Object item, String name) {
        try {
            Field field;
            try {
                field = item.getClass().getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                field = item.getClass().getSuperclass().getDeclaredField(name);
            }
            return field;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }
}
