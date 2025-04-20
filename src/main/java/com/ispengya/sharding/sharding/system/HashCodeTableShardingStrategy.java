package com.ispengya.sharding.sharding.system;

import com.ispengya.sharding.TableShardingStrategy;
import com.ispengya.sharding.sharding.ShardingContextHolder;
import com.ispengya.sharding.sharding.TableShardingRule;

/**
 * @description:
 * @author: hanzhipeng
 * @create: 2025-04-20 14:06
 **/

public class HashCodeTableShardingStrategy implements TableShardingStrategy {
    @Override
    public String shard(TableShardingRule tableShardingRule, Object shardingVal) {
        int size = tableShardingRule.getTableCount();
        int idx = (size - 1) & (shardingVal.hashCode() ^ (shardingVal.hashCode() >>> 16));
        int tbIdx = idx % tableShardingRule.getTableCount();

        return tableShardingRule.getLogicTableName() + "_" + tbIdx;
    }
}
