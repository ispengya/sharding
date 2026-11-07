package com.ispengya.sharding.algorithm;

import com.ispengya.sharding.exception.ShardingException;

import java.util.List;

/**
 * Hash 分片算法（内置，Bean 名 "hash"）：
 * 对键值做 spread hash 后按分片数取模。
 * 数据源场景返回 databases 列表中的数据源名，分表场景返回 {logicTableName}_{下标}。
 * 注意：掩码运算要求分片数为 2 的幂次方才能均匀分布（沿用既有实现）。
 */
public class HashShardingAlgorithm implements ShardingAlgorithm {

    @Override
    public String calculate(ShardingContext context) {
        Object value = context.getShardingValue();
        if (value == null) {
            throw new ShardingException("分片键值为空，无法计算 hash 分片");
        }

        boolean isDb = context.getDatabases() != null;
        int size = isDb ? context.getDatabases().size() : context.getTableCount();
        if (size <= 0) {
            throw new ShardingException("分片数量非法: " + size);
        }

        int h = value.hashCode();
        int idx = (size - 1) & (h ^ (h >>> 16));
        int target = idx % size;

        if (isDb) {
            List<String> databases = context.getDatabases();
            return databases.get(target);
        }
        return context.getLogicTableName() + "_" + target;
    }
}
