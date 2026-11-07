package com.ispengya.sharding.rule;

import lombok.Data;

/**
 * 分表规则：逻辑表名、分表数量、分表键字段名、分表策略标识。
 */
@Data
public class TableShardingRule {
    private int tableCount;
    private String logicTableName;
    private String shardingKey;
    private String strategyKey;
}
