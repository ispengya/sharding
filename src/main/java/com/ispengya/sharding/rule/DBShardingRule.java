package com.ispengya.sharding.rule;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 分库规则：分片范围（databases 列表）、分库键、算法引用与算法参数。
 * databases 的长度即分库数量（不再单独配置 dbCount）。
 */
@Data
public class DBShardingRule {

    private List<String> databases;
    private String shardingKey;
    private String algorithm;
    private Map<String, String> algorithmParams;
}
