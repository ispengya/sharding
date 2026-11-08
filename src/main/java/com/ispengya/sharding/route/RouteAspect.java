package com.ispengya.sharding.route;

import com.ispengya.sharding.annotation.DBRouter;
import com.ispengya.sharding.annotation.Route;
import com.ispengya.sharding.algorithm.ShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingContext;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.rule.DBShardingRule;
import com.ispengya.sharding.rule.ShardingRuleRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由切面：拦截标注 @DBRouter 的方法（方法级优先于类级），职责是"方法级上下文"——
 * <ul>
 *   <li>读写角色 route（SLAVE 在活动事务内直接报错）</li>
 *   <li>方法基线路由：进入方法时按参数预算一次分库落点，供无表 SQL（如 SELECT @@server_id）
 *       与无规则表的语句沿用；同时入口即校验分库键可提取（fail-fast，不等到 SQL 执行）</li>
 * </ul>
 * SQL 触及分片表时，落点由语句级路由（ShardingRewriter.plan，按"这条 SQL + 这份参数"现场计算）
 * 覆盖基线，语句结束恢复——分库键的最终事实依据是 SQL，不是方法注解。
 * 上下文一律保存/恢复：嵌套 @DBRouter 调用返回后外层上下文不丢。
 */
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
        Route route = resolveRouteRole(method, targetClass);
        if (route == Route.SLAVE && TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new ShardingException("route = SLAVE 不能在活动事务内使用（从库延迟会破坏事务一致性）：方法 "
                    + signature(method));
        }
        String baseline = resolveDbRoute(method, jp.getArgs(), targetClass);

        // 保存外层上下文：嵌套调用返回后恢复，而非无条件清空
        Route previousRoute = RouteContextHolder.getRoute();
        String previousDb = RouteContextHolder.getDbHolder();

        RouteContextHolder.setRoute(route);
        if (baseline != null) {
            RouteContextHolder.setDbHolder(baseline);
        } else {
            RouteContextHolder.clearDbHolder();
        }
        try {
            return jp.proceed();
        } finally {
            if (previousRoute != null) {
                RouteContextHolder.setRoute(previousRoute);
            } else {
                RouteContextHolder.clearRoute();
            }
            if (previousDb != null) {
                RouteContextHolder.setDbHolder(previousDb);
            } else {
                RouteContextHolder.clearDbHolder();
            }
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
     * 计算方法基线分库路由（包级可见，便于单测）。
     * 按已配置的分库键（rules 的 shardingKey，按配置顺序）依次尝试从参数提取。
     * 未配置任何分库规则时返回 null（db 直通 primary，仅按 route 做组内角色路由）。
     * 提取不到一律抛 ShardingException（入口 fail-fast）。
     */
    String resolveDbRoute(Method method, Object[] args, Class<?> targetClass) {
        if (registry.getDbRules().isEmpty()) {
            return null;
        }

        List<String> candidateKeys = registry.getDbShardingKeys();
        Object source = normalizeArgs(method, args);
        String matchedKey = null;
        Object value = null;
        for (String key : candidateKeys) {
            Object extracted = ShardingKeyExtractor.extract(source, key);
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
     * 把方法参数规范成与 MyBatis parameterObject 同形的取值源：
     * 单参数直接传原对象；多参数组装 @Param 名 + paramN 的 Map（与 MyBatis ParamMap 同语义）。
     */
    static Object normalizeArgs(Method method, Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        if (args.length == 1) {
            return args[0];
        }
        Map<String, Object> source = new HashMap<>();
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        for (int i = 0; i < args.length; i++) {
            for (Annotation annotation : parameterAnnotations[i]) {
                if (annotation instanceof org.apache.ibatis.annotations.Param) {
                    source.put(((org.apache.ibatis.annotations.Param) annotation).value(), args[i]);
                }
            }
            source.put("param" + (i + 1), args[i]);
        }
        return source;
    }

    private String signature(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }
}
