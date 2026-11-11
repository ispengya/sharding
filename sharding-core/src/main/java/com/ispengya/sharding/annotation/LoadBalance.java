package com.ispengya.sharding.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 负载均衡策略注册标记：标注在实现 LoadBalancer 的类上，
 * 应用组件扫描发现后实例化，value 即配置 dataSources.*.loadBalance 的引用名。
 * 注册套路与 @Algorithm 统一。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface LoadBalance {

    /**
     * 策略名：配置 dataSources.*.loadBalance 按此引用，全容器内不可重复
     */
    String value();
}
