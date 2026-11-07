package com.ispengya.sharding.route;

import com.ispengya.sharding.annotation.DBRouter;
import com.ispengya.sharding.annotation.Route;
import com.ispengya.sharding.algorithm.ShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingContext;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.rule.DBShardingRule;
import com.ispengya.sharding.rule.ShardingRuleRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 分库路由切面：拦截标注 @DBRouter 的方法（方法级优先于类级），
 * 按显式 key 或规则中的分库键从参数提取键值，结合读写角色（route，方法级 > 类级）
 * 写入 RouteContextHolder，方法返回后清理。
 * 校验：route = SLAVE 在活动事务内 → 抛 ShardingException；提取失败一律抛异常，不静默兜底。
 */
@Slf4j
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class RouteAspect {

    private final ShardingRuleRegistry registry;

    public RouteAspect(ShardingRuleRegistry shardingRuleRegistry) {
        this.registry = shardingRuleRegistry;
    }

    @Around("(@annotation(com.ispengya.sharding.annotation.DBRouter) || @within(com.ispengya.sharding.annotation.DBRouter))")
    public Object doRouter(ProceedingJoinPoint jp) throws Throwable {
        Method method = ((MethodSignature) jp.getSignature()).getMethod();
        Class<?> targetClass = jp.getTarget() != null ? jp.getTarget().getClass() : method.getDeclaringClass();
        String dbRouteKey = resolveDbRoute(method, jp.getArgs(), targetClass);
        Route route = resolveRouteRole(method, targetClass);
        if (route == Route.SLAVE && TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new ShardingException("route = SLAVE 不能在活动事务内使用（从库延迟会破坏事务一致性）：方法 "
                    + signature(method));
        }
        RouteContextHolder.setDbHolder(dbRouteKey);
        RouteContextHolder.setRoute(route);
        try {
            return jp.proceed();
        } finally {
            RouteContextHolder.clearDbHolder();
            RouteContextHolder.clearRoute();
        }
    }

    /**
     * 解析读写角色（包级可见，便于单测）：方法级 @DBRouter.route > 类级，均未命中默认 MASTER。
     * 方法上存在注解时即以方法为准（含默认值，便于方法级覆盖类级 SLAVE）。
     */
    Route resolveRouteRole(Method method, Class<?> targetClass) {
        DBRouter annotation = method.getAnnotation(DBRouter.class);
        if (annotation == null && targetClass != null) {
            annotation = targetClass.getAnnotation(DBRouter.class);
        }
        return annotation != null ? annotation.route() : Route.MASTER;
    }

    /**
     * 计算分库路由目标（包级可见，便于单测）。
     * 优先级：方法级 @DBRouter(key) > 类级 > 按规则分库键依次尝试提取。
     * 未配置任何分库规则时返回 null（db 直通 primary，仅按 route 做组内角色路由）。
     */
    String resolveDbRoute(Method method, Object[] args, Class<?> targetClass) {
        if (registry.getDbRules().isEmpty()) {
            return null;
        }

        DBRouter annotation = method.getAnnotation(DBRouter.class);
        if (annotation == null && targetClass != null) {
            annotation = targetClass.getAnnotation(DBRouter.class);
        }
        String explicitKey = annotation != null && StringUtils.isNotBlank(annotation.key())
                ? annotation.key().trim() : null;

        List<String> candidateKeys = explicitKey != null
                ? Collections.singletonList(explicitKey)
                : registry.getDbShardingKeys();

        String matchedKey = null;
        String value = null;
        for (String key : candidateKeys) {
            String extracted = extractValue(key, method, args);
            if (extracted != null) {
                matchedKey = key;
                value = extracted;
                break;
            }
        }
        if (matchedKey == null) {
            throw new ShardingException("无法提取分库键 " + candidateKeys + "：方法 " + signature(method)
                    + " 的参数中不存在对应值");
        }

        DBShardingRule rule = registry.findDbRuleByKey(matchedKey);
        if (rule == null) {
            throw new ShardingException("分库键 [" + matchedKey + "] 未配置于任何分库规则");
        }
        ShardingAlgorithm algorithm = registry.getAlgorithms().get(rule.getAlgorithm());
        return algorithm.calculateDb(ShardingContext.forDb(value, rule.getDatabases(), rule.getAlgorithmParams()));
    }

    /**
     * 按名称从方法参数提取键值：单参数直接取，多参数按 @Param 精确匹配、paramN 约定、POJO 字段依次尝试
     */
    String extractValue(String name, Method method, Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        if (args.length == 1) {
            return extractFromArg(name, args[0]);
        }

        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        for (int i = 0; i < args.length; i++) {
            for (Annotation annotation : parameterAnnotations[i]) {
                if (annotation instanceof org.apache.ibatis.annotations.Param
                        && name.equals(((org.apache.ibatis.annotations.Param) annotation).value())) {
                    return args[i] != null ? String.valueOf(args[i]) : null;
                }
            }
            if (("param" + (i + 1)).equals(name)) {
                return args[i] != null ? String.valueOf(args[i]) : null;
            }
        }
        // 多参数中可能直接传 POJO：按字段名扫描
        for (Object arg : args) {
            if (arg == null || arg instanceof String || arg instanceof Map) {
                continue;
            }
            Object field = readField(arg, name);
            if (field != null) {
                return String.valueOf(field);
            }
        }
        return null;
    }

    private String extractFromArg(String name, Object arg) {
        if (arg == null) {
            return null;
        }
        if (arg instanceof Map) {
            Object value = ((Map<?, ?>) arg).get(name);
            return value != null ? String.valueOf(value) : null;
        }
        if (arg instanceof String) {
            // 单字符串参数即键值本身（与 MyBatis 单参数语义一致）
            return arg.toString();
        }
        Object field = readField(arg, name);
        return field != null ? String.valueOf(field) : null;
    }

    /**
     * 沿父类链安全查找字段（修复旧实现父类为 Object 时的 NPE 吞没问题）
     */
    private Object readField(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                // 继续查父类
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    private String signature(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }
}
