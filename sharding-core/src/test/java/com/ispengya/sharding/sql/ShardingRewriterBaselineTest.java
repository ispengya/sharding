package com.ispengya.sharding.sql;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.util.JdbcConstants;
import com.ispengya.sharding.algorithm.HashShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingContext;
import com.ispengya.sharding.config.ShardingConfig;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.rule.ShardingRuleRegistry;
import com.ispengya.sharding.rule.TableShardingRule;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/**
 * SQL 改写测试：命中规则改写、非分片表直通、JOIN 只改分片侧；
 * 取不到分表键时抛异常（已知问题 #5 在阶段 2 修复后的行为）。
 */
public class ShardingRewriterBaselineTest {

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

        ShardingConfig.ShardingRuleConfig ruleConfig = new ShardingConfig.ShardingRuleConfig();
        ShardingConfig.DbRuleConfig dbRule = new ShardingConfig.DbRuleConfig();
        dbRule.setDatabases(Arrays.asList("ds_0", "ds_1"));
        dbRule.setShardingKey("userId");
        dbRule.setAlgorithm("hash");
        ShardingConfig.TableRuleConfig tableRule = new ShardingConfig.TableRuleConfig();
        tableRule.setShardingKey("orderId");
        tableRule.setAlgorithm("hash");
        tableRule.setTableCount(2);
        ruleConfig.setDb(dbRule);
        ruleConfig.setTable(tableRule);
        properties.getRules().put("t_order", ruleConfig);

        Map<String, ShardingAlgorithm> algorithms = new HashMap<String, ShardingAlgorithm>();
        algorithms.put("hash", algorithm);
        rewriter = new ShardingRewriter(new ShardingRuleRegistry(properties, algorithms));
    }

    private String rewrite(String sql, Object param) {
        List<SQLStatement> stmts = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        stmts.get(0).accept(new ShardingTableVisitor(param, rewriter));
        return SQLUtils.toSQLString(stmts.get(0), JdbcConstants.MYSQL);
    }

    private String expectedTable(String orderId) {
        return algorithm.calculateTable(ShardingContext.forTable(orderId, "t_order", 2, null));
    }

    @Test
    public void shardedTableRewrittenToPhysicalTable() {
        Map<String, Object> param = new HashMap<String, Object>();
        param.put("orderId", "o-1");
        String expected = expectedTable("o-1");

        String sql = rewrite("SELECT id FROM t_order WHERE id = #{id}", param);
        assertTrue("期望改写为 " + expected + "，实际: " + sql, sql.contains(expected));
    }

    @Test
    public void insertStatementAlsoRewritten() {
        Map<String, Object> param = new HashMap<String, Object>();
        param.put("orderId", "o-2");
        String expected = expectedTable("o-2");

        String sql = rewrite("INSERT INTO t_order (id, user_id, order_id) VALUES (#{id}, #{userId}, #{orderId})", param);
        assertTrue("期望改写为 " + expected + "，实际: " + sql, sql.contains(expected));
    }

    @Test
    public void unknownTableLeftUntouched() {
        Map<String, Object> param = new HashMap<String, Object>();
        param.put("orderId", "o-1");
        String sql = rewrite("SELECT id FROM t_user WHERE id = 1", param);
        assertTrue(sql.contains("t_user"));
    }

    @Test
    public void joinRewritesShardedSideOnly() {
        Map<String, Object> param = new HashMap<String, Object>();
        param.put("orderId", "o-1");
        String expected = expectedTable("o-1");

        String sql = rewrite("SELECT u.id FROM t_user u LEFT JOIN t_order o ON u.id = o.id", param);
        assertTrue("join 中分片表应改写，实际: " + sql, sql.contains(expected));
        assertTrue("join 中非分片表不应改写，实际: " + sql, sql.contains("t_user"));
    }

    @Test(expected = ShardingException.class)
    public void missingShardingKeyThrowsInsteadOfSilentlyKeepingLogicTable() {
        // 旧实现静默跳过改写导致运行期表不存在，现改为立即报错（已知问题 #5）
        rewrite("SELECT id FROM t_order WHERE id = 1", new HashMap<String, Object>());
    }
}
