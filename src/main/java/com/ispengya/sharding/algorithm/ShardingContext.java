package com.ispengya.sharding.algorithm;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 分片计算上下文：携带分片键值与规则信息。
 * 数据源场景 databases 非空；分表场景 logicTableName/tableCount 非空；二者互斥。
 */
@Data
public class ShardingContext {

    private final Object shardingValue;
    private final List<String> databases;
    private final String logicTableName;
    private final int tableCount;
    private final Map<String, String> algorithmParams;

    private ShardingContext(Object shardingValue, List<String> databases,
                            String logicTableName, int tableCount, Map<String, String> algorithmParams) {
        this.shardingValue = shardingValue;
        this.databases = databases;
        this.logicTableName = logicTableName;
        this.tableCount = tableCount;
        this.algorithmParams = algorithmParams;
    }

    /**
     * 数据源分片上下文
     */
    public static ShardingContext forDb(Object value, List<String> databases, Map<String, String> params) {
        return new ShardingContext(value, databases, null, 0, params);
    }

    /**
     * 分表上下文
     */
    public static ShardingContext forTable(Object value, String logicTableName, int tableCount, Map<String, String> params) {
        return new ShardingContext(value, null, logicTableName, tableCount, params);
    }
}
