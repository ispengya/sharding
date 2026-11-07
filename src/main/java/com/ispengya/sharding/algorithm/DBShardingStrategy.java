package com.ispengya.sharding.algorithm;

import com.ispengya.sharding.rule.DBShardingRule;

public interface DBShardingStrategy {
    String shard(DBShardingRule dbShardingRule, String shardingValue);
}
