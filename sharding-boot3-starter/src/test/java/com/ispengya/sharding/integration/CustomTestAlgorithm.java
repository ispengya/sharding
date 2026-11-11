package com.ispengya.sharding.integration;

import com.ispengya.sharding.algorithm.ShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingContext;
import com.ispengya.sharding.annotation.Algorithm;

/**
 * 自定义分片算法样例：仅靠 @Algorithm 注解注册（无 @Component、无 Bean 方法），
 * 由应用组件扫描发现，验证"扫注解实例化注入"路径。
 */
@Algorithm("test_custom")
public class CustomTestAlgorithm implements ShardingAlgorithm {

    @Override
    public String calculateDb(ShardingContext context) {
        return context.getDatabases().get(0);
    }

    @Override
    public String calculateTable(ShardingContext context) {
        return context.getLogicTableName() + "_custom";
    }
}
