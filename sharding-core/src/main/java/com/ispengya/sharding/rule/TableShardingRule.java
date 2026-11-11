package com.ispengya.sharding.rule;

import lombok.Data;

import java.util.Map;

/**
 * 分表规则：逻辑表名、分表数量、分表键、算法引用与算法参数。
 */
@Data
public class TableShardingRule {

    private String logicTableName;
    private int tableCount;
    private String shardingKey;
    private String algorithm;
    private Map<String, String> algorithmParams;
}
