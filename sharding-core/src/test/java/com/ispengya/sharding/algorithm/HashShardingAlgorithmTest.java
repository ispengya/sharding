package com.ispengya.sharding.algorithm;

import com.ispengya.sharding.exception.ShardingException;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
            String ds = algorithm.calculateDb(ShardingContext.forDb("user-" + i, Arrays.asList("ds_0", "ds_1"), null));
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
                algorithm.calculateDb(ShardingContext.forDb("user-42", Arrays.asList("ds_0", "ds_1"), null)),
                algorithm.calculateDb(ShardingContext.forDb("user-42", Arrays.asList("ds_0", "ds_1"), null)));
    }

    @Test
    public void tableStrategyRenamesLogicTable() {
        Set<String> routed = new HashSet<String>();
        for (int i = 0; i < 100; i++) {
            String table = algorithm.calculateTable(ShardingContext.forTable("order-" + i, "t_order", 2, null));
            assertTrue("越界物理表: " + table, "t_order_0".equals(table) || "t_order_1".equals(table));
            routed.add(table);
        }
        assertEquals(2, routed.size());
    }

    @Test
    public void nullValueThrows() {
        try {
            algorithm.calculateDb(ShardingContext.forDb(null, Arrays.asList("ds_0", "ds_1"), null));
            fail("空键值应抛出 ShardingException");
        } catch (ShardingException expected) {
            // 既有行为：空值报错
        }
    }

    @Test
    public void collectionValueThrowsInsteadOfSilentRoute() {
        // 修复缺口：IN 类集合键值无法确定唯一落点——报错，而非按 List 整体 hashCode 静默路由
        try {
            algorithm.calculateDb(
                    ShardingContext.forDb(Arrays.asList("u-1", "u-2"), Arrays.asList("ds_0", "ds_1"), null));
            fail("集合键值应抛出 ShardingException");
        } catch (ShardingException expected) {
            assertTrue(expected.getMessage().contains("单值"));
        }
    }

    @Test
    public void arrayValueThrowsInsteadOfSilentRoute() {
        try {
            algorithm.calculateDb(
                    ShardingContext.forDb(new String[]{"u-1"}, Arrays.asList("ds_0", "ds_1"), null));
            fail("数组键值应抛出 ShardingException");
        } catch (ShardingException expected) {
            // 数组 hashCode 为 identity，静默路由跨重启不稳定
        }
    }

    @Test
    public void mapValueThrowsInsteadOfSilentRoute() {
        // 嵌套 Map 作为键值（如 {"userId": {...}} 的内层）同样无法单值路由
        try {
            algorithm.calculateDb(
                    ShardingContext.forDb(new HashMap<String, Object>(), Arrays.asList("ds_0", "ds_1"), null));
            fail("Map 键值应抛出 ShardingException");
        } catch (ShardingException expected) {
            // Map 整体 hashCode 语义不明
        }
    }
}

