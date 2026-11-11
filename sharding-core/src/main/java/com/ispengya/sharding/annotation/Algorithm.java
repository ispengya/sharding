package com.ispengya.sharding.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 分片算法注册标记：标注在实现 ShardingAlgorithm 的类上，
 * 应用组件扫描发现后实例化，value 即配置 rules.*.algorithm 的引用名（不再依赖 Bean 名约定）。
 * scope 声明算法允许的规则段，启动期校验拦截错配（如仅分库算法配到 table 段）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface Algorithm {

    /**
     * 算法名：配置 rules.*.db.algorithm / rules.*.table.algorithm 按此引用，全容器内不可重复
     */
    String value();

    /**
     * 允许的规则段，默认两侧都可用
     */
    Scope scope() default Scope.BOTH;

    /**
     * 算法规则段：DB = 仅分库，TABLE = 仅分表，BOTH = 通用
     */
    enum Scope {
        DB, TABLE, BOTH
    }
}
