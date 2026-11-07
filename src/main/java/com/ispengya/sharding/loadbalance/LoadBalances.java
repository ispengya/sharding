package com.ispengya.sharding.loadbalance;

import com.ispengya.sharding.annotation.LoadBalance;
import com.ispengya.sharding.exception.ShardingException;
import org.springframework.util.ClassUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 负载均衡策略收集器：把容器中全部 LoadBalancer 实例按各自 @LoadBalance 注解的
 * value 收敛为"引用名 → 实例"的注册表。未标注注解、名称为空或重名均启动期报错。
 * 与 ShardingAlgorithms 同套路。
 */
public final class LoadBalances {

    private LoadBalances() {
    }

    public static Map<String, LoadBalancer> collect(Collection<LoadBalancer> candidates) {
        Map<String, LoadBalancer> byName = new LinkedHashMap<String, LoadBalancer>();
        if (candidates == null || candidates.isEmpty()) {
            return byName;
        }
        for (LoadBalancer candidate : candidates) {
            Class<?> userClass = ClassUtils.getUserClass(candidate);
            LoadBalance annotation = userClass.getAnnotation(LoadBalance.class);
            if (annotation == null) {
                throw new ShardingException("负载均衡策略 " + userClass.getName()
                        + " 未标注 @LoadBalance(\"名字\")，无法确定配置引用名");
            }
            String name = annotation.value() == null ? "" : annotation.value().trim();
            if (name.isEmpty()) {
                throw new ShardingException("负载均衡策略 " + userClass.getName() + " 的 @LoadBalance 名称不能为空");
            }
            LoadBalancer previous = byName.put(name, candidate);
            if (previous != null) {
                throw new ShardingException("负载均衡策略名重复: " + name + "（"
                        + ClassUtils.getUserClass(previous).getName() + " 与 "
                        + userClass.getName() + "）");
            }
        }
        return byName;
    }
}
