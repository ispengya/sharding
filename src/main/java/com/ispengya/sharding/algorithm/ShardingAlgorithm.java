package com.ispengya.sharding.algorithm;

/**
 * 分片算法 SPI：根据上下文计算目标分片名。
 * 数据源规则返回 dataSources 中的逻辑数据源名，分表规则返回物理表名。
 * 实现类注册为 Spring Bean，Bean 名即配置中 algorithm 字段的引用名。
 */
public interface ShardingAlgorithm {

    String calculate(ShardingContext context);
}
