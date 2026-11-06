package com.ispengya.sharding.rule;

import lombok.Data;

/**
 * @description:
 * @author: hanzhipeng
 * @create: 2025-04-20 12:51
 **/
@Data
public class TableShardingRule {
    private int tableCount;
    private String logicTableName;
    private String shardingKey;
    private String strategyKey;
}
