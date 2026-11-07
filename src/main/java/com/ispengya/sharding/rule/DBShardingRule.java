package com.ispengya.sharding.rule;

import lombok.Data;

/**
 * 分库规则：分库数量、分库键字段名、分库策略标识。
 */
@Data
public class DBShardingRule {
    private int dbCount;
    private String shardingKey;
    private String strategyKey;
}
