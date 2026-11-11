package com.ispengya.sharding.sql;

import com.ispengya.sharding.algorithm.HashShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingContext;
import com.ispengya.sharding.config.ShardingConfig;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.route.RouteContextHolder;
import com.ispengya.sharding.rule.ShardingRuleRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 语句级分库路由测试（方案 B 核心行为）：
 * 路由跟着"这条 SQL + 这份参数"走——未标注解也按表规则现场计算；
 * 缺分库键、同 SQL 多表路由不一致、解析失败一律报错；无规则/无表 SQL 不产生落点。
 */
public class StatementPlanRoutingTest {

    private ShardingRewriter rewriter;
    private final HashShardingAlgorithm algorithm = new HashShardingAlgorithm();

    @Before
    public void setUp() {
        ShardingConfig properties = new ShardingConfig();
        properties.setPrimary("ds_0");
        ShardingConfig.DataSourceGroup ds0 = new ShardingConfig.DataSourceGroup();
        ds0.setMaster(new ShardingConfig.NodeConfig());
        ShardingConfig.DataSourceGroup ds1 = new ShardingConfig.DataSourceGroup();
        ds1.setMaster(new ShardingConfig.NodeConfig());
        properties.getDataSources().put("ds_0", ds0);
        properties.getDataSources().put("ds_1", ds1);

        // t_order：分库键 userId + 分表键 orderId
        ShardingConfig.ShardingRuleConfig orderRule = new ShardingConfig.ShardingRuleConfig();
        ShardingConfig.DbRuleConfig orderDb = new ShardingConfig.DbRuleConfig();
        orderDb.setDatabases(Arrays.asList("ds_0", "ds_1"));
        orderDb.setShardingKey("userId");
        orderDb.setAlgorithm("hash");
        ShardingConfig.TableRuleConfig orderTable = new ShardingConfig.TableRuleConfig();
        orderTable.setShardingKey("orderId");
        orderTable.setAlgorithm("hash");
        orderTable.setTableCount(2);
        orderRule.setDb(orderDb);
        orderRule.setTable(orderTable);
        properties.getRules().put("t_order", orderRule);

        // t_pay：仅分库（键 payId），用于多表一致性校验
        ShardingConfig.ShardingRuleConfig payRule = new ShardingConfig.ShardingRuleConfig();
        ShardingConfig.DbRuleConfig payDb = new ShardingConfig.DbRuleConfig();
        payDb.setDatabases(Arrays.asList("ds_0", "ds_1"));
        payDb.setShardingKey("payId");
        payDb.setAlgorithm("hash");
        payRule.setDb(payDb);
        properties.getRules().put("t_pay", payRule);

        Map<String, ShardingAlgorithm> algorithms = new HashMap<String, ShardingAlgorithm>();
        algorithms.put("hash", algorithm);
        rewriter = new ShardingRewriter(new ShardingRuleRegistry(properties, algorithms));
    }

    @After
    public void tearDown() {
        RouteContextHolder.clearDbHolder();
    }

    private String expectedDs(String value) {
        return algorithm.calculateDb(
                ShardingContext.forDb(value, Arrays.asList("ds_0", "ds_1"), null));
    }

    private String valueForDs(String target) {
        for (int i = 0; i < 200; i++) {
            String candidate = "v-" + i;
            if (target.equals(expectedDs(candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException("200 次内未找到路由到 " + target + " 的值");
    }

    @Test
    public void routeComputedFromSqlAndParamsWithoutAnyAnnotation() {
        // 语句级路由不依赖注解：SQL 碰 t_order → 按表规则取 userId
        Map<String, Object> param = new HashMap<>();
        param.put("userId", "u-1");
        param.put("orderId", "o-1");

        ShardingRewriter.StatementPlan plan = rewriter.plan(
                "INSERT INTO t_order (id, user_id, order_id) VALUES (#{id}, #{userId}, #{orderId})", param);

        assertEquals(expectedDs("u-1"), plan.getDbRoute());
        assertTrue("应改写为物理表，实际: " + plan.getSql(),
                plan.getSql().contains("t_order_"));
    }

    @Test
    public void missingDbKeyThrowsAtPlanTime() {
        Map<String, Object> param = new HashMap<>();
        param.put("orderId", "o-1"); // 缺 userId
        try {
            rewriter.plan("SELECT id FROM t_order WHERE order_id = #{orderId}", param);
            fail("缺分库键应抛出 ShardingException");
        } catch (ShardingException expected) {
            // 语句级 fail-fast
        }
    }

    @Test
    public void tableWithoutDbRuleYieldsNullRoute() {
        Map<String, Object> param = new HashMap<>();
        param.put("userId", "u-1");

        ShardingRewriter.StatementPlan plan = rewriter.plan("SELECT id FROM t_user WHERE id = 1", param);

        assertNull(plan.getDbRoute());
        assertTrue(plan.getSql().contains("t_user"));
    }

    @Test
    public void tablelessSqlYieldsNullRoute() {
        // 无表 SQL（读写分离探针）不产生语句级落点——由方法基线接管
        ShardingRewriter.StatementPlan plan = rewriter.plan("SELECT @@server_id", new HashMap<>());
        assertNull(plan.getDbRoute());
    }

    @Test
    public void singleStringParamServesAsKeyForBothSides() {
        // 单字符串参数即键值本身（分库、分表同一语义）
        ShardingRewriter.StatementPlan plan = rewriter.plan("SELECT id FROM t_order", "u-9");
        assertEquals(expectedDs("u-9"), plan.getDbRoute());
        assertTrue(plan.getSql().contains(
                algorithm.calculateTable(ShardingContext.forTable("u-9", "t_order", 2, null))));
    }

    @Test
    public void inconsistentRoutesAcrossTablesThrow() {
        // JOIN 两张分片表，参数使各自落点不同 → 报错（跨库 JOIN 不支持）
        Map<String, Object> param = new HashMap<>();
        param.put("userId", valueForDs("ds_0"));
        param.put("orderId", "o-1");
        param.put("payId", valueForDs("ds_1"));

        try {
            rewriter.plan("SELECT * FROM t_order o JOIN t_pay p ON o.id = p.id", param);
            fail("多表路由不一致应抛出 ShardingException");
        } catch (ShardingException expected) {
            // 期望的校验错误
        }
    }

    @Test
    public void consistentRoutesAcrossTablesAllowed() {
        // JOIN 两张分片表但落点相同 → 放行
        Map<String, Object> param = new HashMap<>();
        String sameValue = valueForDs("ds_1");
        param.put("userId", sameValue);
        param.put("orderId", "o-1");
        param.put("payId", sameValue);

        ShardingRewriter.StatementPlan plan =
                rewriter.plan("SELECT * FROM t_order o JOIN t_pay p ON o.id = p.id", param);
        assertEquals("ds_1", plan.getDbRoute());
    }

    @Test
    public void noRulesFastPathSkipsParsing() {
        ShardingConfig empty = new ShardingConfig();
        empty.setPrimary("ds_0");
        ShardingConfig.DataSourceGroup group = new ShardingConfig.DataSourceGroup();
        group.setMaster(new ShardingConfig.NodeConfig());
        empty.getDataSources().put("ds_0", group);
        ShardingRewriter plain = new ShardingRewriter(
                new ShardingRuleRegistry(empty, new HashMap<String, ShardingAlgorithm>()));

        // 无规则：不解析、不报错，原样直通
        ShardingRewriter.StatementPlan plan = plain.plan("%%% not even sql %%%", new HashMap<>());
        assertEquals("%%% not even sql %%%", plan.getSql());
        assertNull(plan.getDbRoute());
    }

    @Test
    public void parseFailureWithRulesThrowsInsteadOfSilentlyPrimary() {
        try {
            rewriter.plan("INSERT INTO t_order VALUES (", new HashMap<>());
            fail("已配置规则时解析失败应抛出 ShardingException");
        } catch (ShardingException expected) {
            // 无法确定路由不静默兜底
        }
    }

    @Test
    public void planDoesNotTouchContextHolderUntilInterceptorAppliesIt() {
        // plan 只计算、不写上下文；写入/恢复由拦截器负责
        Map<String, Object> param = new HashMap<>();
        param.put("userId", "u-1");
        param.put("orderId", "o-1");
        rewriter.plan("SELECT id FROM t_order", param);
        assertNull(RouteContextHolder.getDbHolder());
        assertNotNull(rewriter.routeForTable("t_order", param));
    }
}
