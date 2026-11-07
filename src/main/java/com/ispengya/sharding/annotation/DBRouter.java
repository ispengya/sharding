package com.ispengya.sharding.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记于 Mapper 接口或方法（方法级优先于类级，标注到普通 Bean 启动即报错）：
 * 触发分库路由切面，方法进入时解析分库键与读写角色并写入路由上下文，结束时清理。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DBRouter {

    /**
     * 分库键的参数名（@Param 名或字段名）。
     * 留空时按已配置分库规则的 shardingKey 依次尝试提取；均取不到值则抛出异常。
     * 未配置任何分库规则时不需要分库键（db 直通 primary，仅按 route 做组内角色路由）。
     */
    String key() default "";

    /**
     * 读写角色（方法级 > 类级）：默认 MASTER；SLAVE 走从库且不得处于活动事务内。
     */
    Route route() default Route.MASTER;
}
