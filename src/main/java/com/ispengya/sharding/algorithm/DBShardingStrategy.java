package com.ispengya.sharding.algorithm;

import com.ispengya.sharding.rule.DBShardingRule;

/**
 * @description:
 * @author: hanzhipeng
 * @create: 2025-04-20 17:06
 **/
public interface DBShardingStrategy {
    String shard(DBShardingRule dbShardingRule, String shardingValue);
}
