package com.ispengya.sharding;

import com.ispengya.sharding.sharding.TableShardingRule;

public interface TableShardingStrategy {
    String shard(TableShardingRule tableShardingRule, Object shardingVal);
}