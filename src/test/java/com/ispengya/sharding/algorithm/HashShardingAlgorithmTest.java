package com.ispengya.sharding.algorithm;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Hash 分片算法测试：锁定输出形态（数据源名来自 databases 列表、物理表名 {logic}_{下标}），
 * 断言值与阶段 0 基线一致。
 */
public class HashShardingAlgorithmTest {

    private final HashShardingAlgorithm algorithm = new HashShardingAlgorithm();

    @Test
    public void dbStrategyStaysWithinConfiguredRange() {
        Set<String> routed = new HashSet<String>();
        for (int i = 0; i < 100; i++) {
            String ds = algorithm.calculate(ShardingContext.forDb("user-" + i, Arrays.asList("ds_0", "ds_1"), null));
            assertTrue("越界分片: " + ds, "ds_0".equals(ds) || "ds_1".equals(ds));
            routed.add(ds);
        }
        assertEquals(2, routed.size());
        assertTrue(routed.contains("ds_0"));
        assertTrue(routed.contains("ds_1"));
    }

    @Test
    public void dbStrategyIsDeterministic() {
        assertEquals(
                algorithm.calculate(ShardingContext.forDb("user-42", Arrays.asList("ds_0", "ds_1"), null)),
                algorithm.calculate(ShardingContext.forDb("user-42", Arrays.asList("ds_0", "ds_1"), null)));
    }

    @Test
    public void tableStrategyRenamesLogicTable() {
        Set<String> routed = new HashSet<String>();
        for (int i = 0; i < 100; i++) {
            String table = algorithm.calculate(ShardingContext.forTable("order-" + i, "t_order", 2, null));
            assertTrue("越界物理表: " + table, "t_order_0".equals(table) || "t_order_1".equals(table));
            routed.add(table);
        }
        assertEquals(2, routed.size());
    }
}
