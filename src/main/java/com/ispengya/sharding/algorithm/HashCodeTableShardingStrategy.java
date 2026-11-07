package com.ispengya.sharding.algorithm;

import com.ispengya.sharding.algorithm.TableShardingStrategy;
import com.ispengya.sharding.rule.TableShardingRule;


public class HashCodeTableShardingStrategy implements TableShardingStrategy {
    @Override
    public String shard(TableShardingRule tableShardingRule, Object shardingVal) {
        int size = tableShardingRule.getTableCount();
        int idx = (size - 1) & (shardingVal.hashCode() ^ (shardingVal.hashCode() >>> 16));
        int tbIdx = idx % tableShardingRule.getTableCount();

        return tableShardingRule.getLogicTableName() + "_" + tbIdx;
    }
}
