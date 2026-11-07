package com.ispengya.sharding.algorithm;

import com.ispengya.sharding.annotation.Algorithm;
import com.ispengya.sharding.exception.ShardingException;
import org.springframework.util.ClassUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 分片算法收集器：把容器中全部 ShardingAlgorithm 实例按各自 @Algorithm 注解的
 * value 收敛为"引用名 → 实例"的注册表。未标注注解、名称为空或重名均启动期报错。
 */
public final class ShardingAlgorithms {

    private ShardingAlgorithms() {
    }

    public static Map<String, ShardingAlgorithm> collect(Collection<ShardingAlgorithm> candidates) {
        Map<String, ShardingAlgorithm> byName = new LinkedHashMap<String, ShardingAlgorithm>();
        if (candidates == null || candidates.isEmpty()) {
            return byName;
        }
        for (ShardingAlgorithm candidate : candidates) {
            Class<?> userClass = ClassUtils.getUserClass(candidate);
            Algorithm annotation = userClass.getAnnotation(Algorithm.class);
            if (annotation == null) {
                throw new ShardingException("分片算法 " + userClass.getName()
                        + " 未标注 @Algorithm(\"名字\")，无法确定配置引用名");
            }
            String name = annotation.value() == null ? "" : annotation.value().trim();
            if (name.isEmpty()) {
                throw new ShardingException("分片算法 " + userClass.getName() + " 的 @Algorithm 名称不能为空");
            }
            ShardingAlgorithm previous = byName.put(name, candidate);
            if (previous != null) {
                throw new ShardingException("分片算法名重复: " + name + "（"
                        + ClassUtils.getUserClass(previous).getName() + " 与 "
                        + userClass.getName() + "）");
            }
        }
        return byName;
    }
}
