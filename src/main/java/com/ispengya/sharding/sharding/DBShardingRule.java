package com.ispengya.sharding.sharding;

import lombok.Data;

/**
 * @description:
 * @author: hanzhipeng
 * @create: 2025-04-20 17:07
 **/
@Data
public class DBShardingRule {
    private int dbCount;
    private String shardingKey;
    private String strategyKey;
}
