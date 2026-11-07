package com.ispengya.sharding.algorithm;

/**
 * 分片算法 SPI：分库与分表分成两个方法，实现类按需各自完成，不再靠上下文字段自行二分。
 * 注册方式：类上标注 {@code @Algorithm("名字")}，由应用组件扫描发现，
 * 配置 rules.*.algorithm 按该名字引用。
 * 返回值契约：calculateDb 返回 dataSources 中的逻辑数据源名，calculateTable 返回物理表名。
 * 仅支持一侧的算法在另一侧显式抛 ShardingException，规则侧另由 @Algorithm.scope 启动期拦截。
 */
public interface ShardingAlgorithm {

    /**
     * 分库计算：context 由 ShardingContext.forDb 构造（databases 非空）
     */
    String calculateDb(ShardingContext context);

    /**
     * 分表计算：context 由 ShardingContext.forTable 构造（logicTableName/tableCount 非空）
     */
    String calculateTable(ShardingContext context);
}
