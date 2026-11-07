package com.ispengya.sharding.algorithm;

import com.ispengya.sharding.algorithm.DBShardingStrategy;
import com.ispengya.sharding.rule.DBShardingRule;

public class HashCodeDBShardingStrategy implements DBShardingStrategy {
    @Override
    public String shard(DBShardingRule dbShardingRule, String shardingValue) {
        int size = dbShardingRule.getDbCount();
        int idx = (size - 1) & (shardingValue.hashCode() ^ (shardingValue.hashCode() >>> 16));
        int dbIdx = idx % dbShardingRule.getDbCount();
        return "ds_" + dbIdx;
    }
}
