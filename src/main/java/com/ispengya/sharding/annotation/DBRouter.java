package com.ispengya.sharding.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记于 Mapper 接口或方法（方法级优先于类级，标注到普通 Bean 启动即报错）。
 *
 * <p>分库路由的事实依据是"这条 SQL + 这份参数"：SQL 触及分片表时由语句级路由
 * （ShardingRewriter.plan）按表规则的 shardingKey 现场计算，<b>不依赖本注解</b>——
 * 未标注解的 Mapper 方法写/读分片表同样正确落库。</p>
 *
 * <p>本注解提供两件方法级的事情：</p>
 * <ul>
 *   <li><b>方法基线</b>：进入方法时按参数预算一次分库落点，供无表 SQL
 *       （如 {@code SELECT @@server_id}）与无规则表的语句沿用；同时入口即校验
 *       分库键可提取（取不到立刻报错，不等到 SQL 执行）</li>
 *   <li><b>route</b>：读写角色（SLAVE 走从库，活动事务内禁用）</li>
 * </ul>
 *
 * <p>取键名固定来自配置：语句级用该表 {@code rules.*.db.shardingKey}，
 * 方法基线按已配置的分库键依次尝试——注解上不提供显式指定。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DBRouter {

    /**
     * 读写角色（方法级 > 类级）：默认 MASTER；SLAVE 走从库且不得处于活动事务内。
     */
    Route route() default Route.MASTER;
}
