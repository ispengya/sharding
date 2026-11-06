package com.ispengya.sharding.algorithm;

import com.ispengya.sharding.rule.DBShardingRule;
import com.ispengya.sharding.rule.TableShardingRule;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 分片算法行为基线：锁定当前 hash 策略的输出形态，
 * 阶段 1（结构重构）与阶段 2（配置重构）不得改变这些行为。
 *
 * 基线事实（与 README 不一致处见 docs/journal.md 已知问题）：
 * - 分库策略返回 ds_0 .. ds_{dbCount-1}（0 起始，README 示例为 1 起始）
 * - 分表策略返回 {logicTableName}_{0..tableCount-1}
 */
public class HashCodeStrategyBaselineTest {

    private final HashCodeDBShardingStrategy dbStrategy = new HashCodeDBShardingStrategy();
    private final HashCodeTableShardingStrategy tableStrategy = new HashCodeTableShardingStrategy();

    @Test
    public void dbStrategyStaysWithinConfiguredRange() {
        DBShardingRule rule = new DBShardingRule();
        rule.setDbCount(2);
        rule.setShardingKey("userId");
        rule.setStrategyKey("hash");

        Set<String> routed = new HashSet<String>();
        for (int i = 0; i < 100; i++) {
            String ds = dbStrategy.shard(rule, "user-" + i);
            assertTrue("越界分片: " + ds, "ds_0".equals(ds) || "ds_1".equals(ds));
            routed.add(ds);
        }
        assertEquals(2, routed.size());
        assertTrue(routed.contains("ds_0"));
        assertTrue(routed.contains("ds_1"));
    }

    @Test
    public void dbStrategyIsDeterministic() {
        DBShardingRule rule = new DBShardingRule();
        rule.setDbCount(2);
        assertEquals(dbStrategy.shard(rule, "user-42"), dbStrategy.shard(rule, "user-42"));
    }

    @Test
    public void tableStrategyRenamesLogicTable() {
        TableShardingRule rule = new TableShardingRule();
        rule.setLogicTableName("t_order");
        rule.setTableCount(2);
        rule.setShardingKey("orderId");

        Set<String> routed = new HashSet<String>();
        for (int i = 0; i < 100; i++) {
            String table = tableStrategy.shard(rule, "order-" + i);
            assertTrue("越界物理表: " + table, "t_order_0".equals(table) || "t_order_1".equals(table));
            routed.add(table);
        }
        assertEquals(2, routed.size());
    }
}
