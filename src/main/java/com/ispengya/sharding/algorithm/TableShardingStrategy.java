package com.ispengya.sharding.algorithm;

import com.ispengya.sharding.rule.TableShardingRule;

public interface TableShardingStrategy {
    String shard(TableShardingRule tableShardingRule, Object shardingVal);
}