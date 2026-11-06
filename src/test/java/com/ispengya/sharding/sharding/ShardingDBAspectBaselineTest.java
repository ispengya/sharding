package com.ispengya.sharding.sharding;

import com.ispengya.sharding.config.ShardingProperties;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 分库键提取行为基线（ShardingDBAspect.getAttrValue）。
 *
 * 基线事实（已知问题，见 docs/journal.md）：
 * - 多字符串参数取到字面量 "null"（String.valueOf(null)）
 * - 无对应字段的普通 Bean：getSuperclass 为 null 触发 NPE，被切面 catch 吞掉返回 null
 * 阶段 2 改造为 @Param 精确匹配 + 失败报错后，本组断言需同步更新。
 */
public class ShardingDBAspectBaselineTest {

    public static class Order {
        private String userId = "u-100";
        private String orderId = "o-100";
    }

    private ShardingDBAspect aspect;

    @Before
    public void setUp() {
        ShardingProperties properties = new ShardingProperties();
        properties.setPrimary("ds_0");
        ShardingProperties.DBShardingConfig db = new ShardingProperties.DBShardingConfig();
        db.setDbCount(2);
        db.setShardingKey("userId");
        db.setStrategyKey("hash");
        properties.setDbSharding(db);
        ShardingProperties.TableShardingConfig table = new ShardingProperties.TableShardingConfig();
        table.setLogicTableName("t_order");
        table.setShardingKey("orderId");
        table.setTableCount(2);
        table.setStrategyKey("hash");
        Map<String, ShardingProperties.TableShardingConfig> tables = new HashMap<String, ShardingProperties.TableShardingConfig>();
        tables.put("t_order", table);
        properties.setTableSharding(tables);

        aspect = new ShardingDBAspect(new ShardingRuleRegistry(properties));
    }

    @Test
    public void singleStringParamReturnedAsIs() {
        assertEquals("u-1", aspect.getAttrValue("userId", new Object[]{"u-1"}));
    }

    @Test
    public void beanFieldExtractedByName() {
        assertEquals("u-100", aspect.getAttrValue("userId", new Object[]{new Order()}));
    }

    @Test
    public void multiStringParamsBaselineGivesLiteralNull() {
        // 已知问题：反射拿不到 String 上的 userId 字段后，String.valueOf(null) 得到 "null"
        assertEquals("null", aspect.getAttrValue("userId", new Object[]{"u-1", "o-1"}));
    }

    @Test
    public void beanWithoutFieldBaselineSwallowsToNull() {
        // 已知问题：Object.class.getSuperclass() 为 null 触发 NPE，被切面 catch 吞掉返回 null
        assertNull(aspect.getAttrValue("userId", new Object[]{new Object()}));
    }
}
