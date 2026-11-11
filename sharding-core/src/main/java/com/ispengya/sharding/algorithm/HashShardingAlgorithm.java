package com.ispengya.sharding.algorithm;

import com.ispengya.sharding.annotation.Algorithm;
import com.ispengya.sharding.exception.ShardingException;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Hash 分片算法（内置，算法名 "hash"）：
 * 对键值做 spread hash 后按分片数取模。
 * 数据源场景返回 databases 列表中的数据源名，分表场景返回 {logicTableName}_{下标}。
 * 键值必须是单值：集合 / 数组 / Map 无法确定唯一落点，直接报错（不静默按整体 hash 路由）。
 * 注意：掩码运算要求分片数为 2 的幂次方才能均匀分布（沿用既有实现）。
 */
@Algorithm("hash")
public class HashShardingAlgorithm implements ShardingAlgorithm {

    @Override
    public String calculateDb(ShardingContext context) {
        List<String> databases = context.getDatabases();
        if (databases == null || databases.isEmpty()) {
            throw new ShardingException("hash 分库计算缺少 databases 列表");
        }
        return databases.get(spreadIndex(context, databases.size()));
    }

    @Override
    public String calculateTable(ShardingContext context) {
        int size = context.getTableCount();
        if (size <= 0) {
            throw new ShardingException("分表数量非法: " + size);
        }
        return context.getLogicTableName() + "_" + spreadIndex(context, size);
    }

    private int spreadIndex(ShardingContext context, int size) {
        Object value = context.getShardingValue();
        if (value == null) {
            throw new ShardingException("分片键值为空，无法计算 hash 分片");
        }
        if (value instanceof Collection || value instanceof Map || value.getClass().isArray()) {
            throw new ShardingException("hash 分片键值必须是单值（集合/数组/Map 无法确定唯一落点）: "
                    + value.getClass().getSimpleName());
        }
        int h = value.hashCode();
        int idx = (size - 1) & (h ^ (h >>> 16));
        return idx % size;
    }
}
