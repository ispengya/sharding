package com.ispengya.sharding.sharding.system;

import com.ispengya.sharding.ShardingRouteStrategy;
import com.ispengya.sharding.sharding.ShardingContextHolder;
import com.ispengya.sharding.sharding.ShardingRule;

/**
 * @description:
 * @author: hanzhipeng
 * @create: 2025-04-20 14:06
 **/
public class HashCodeShardingRouteStrategy implements ShardingRouteStrategy {
    @Override
    public String shard(ShardingRule shardingRule, Object shardingVal) {
        int size = shardingRule.getDbCount() * shardingRule.getTableCount();
        int idx = (size - 1) & (shardingVal.hashCode() ^ (shardingVal.hashCode() >>> 16));
        int dbIdx = idx / shardingRule.getTableCount() + 1;
        int tbIdx = idx - shardingRule.getTableCount() * (dbIdx - 1);

        ShardingContextHolder.setDbHolder("ds_" + dbIdx);

        return shardingRule.getLogicTableName() + "_" + tbIdx;
    }
}
