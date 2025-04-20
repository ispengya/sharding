package com.ispengya.sharding;

import com.ispengya.sharding.sharding.ShardingRule;

public interface ShardingRouteStrategy {
    String shard(ShardingRule shardingRule, Object shardingVal);
}