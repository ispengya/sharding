package com.ispengya.sharding.sharding;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.util.JdbcConstants;
import com.ispengya.sharding.config.ShardingProperties;
import com.ispengya.sharding.sharding.system.HashCodeTableShardingStrategy;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * SQL 改写行为基线（ShardingRewriter + ShardingTableVisitor）。
 *
 * 基线事实：分片键取不到值时静默跳过改写（逻辑表名原样输出，
 * 执行期会因物理表不存在而失败）——已知问题，见 docs/journal.md。
 */
public class ShardingRewriterBaselineTest {

    private ShardingRewriter rewriter;
    private final HashCodeTableShardingStrategy tableStrategy = new HashCodeTableShardingStrategy();

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

        rewriter = new ShardingRewriter(new ShardingRuleRegistry(properties));
    }

    private String rewrite(String sql, Object param) {
        List<SQLStatement> stmts = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        stmts.get(0).accept(new ShardingTableVisitor(param, rewriter));
        return SQLUtils.toSQLString(stmts.get(0), JdbcConstants.MYSQL);
    }

    private TableShardingRule tableRule() {
        TableShardingRule rule = new TableShardingRule();
        rule.setLogicTableName("t_order");
        rule.setTableCount(2);
        rule.setShardingKey("orderId");
        return rule;
    }

    @Test
    public void shardedTableRewrittenToPhysicalTable() {
        Map<String, Object> param = new HashMap<String, Object>();
        param.put("orderId", "o-1");
        String expected = tableStrategy.shard(tableRule(), "o-1");

        String sql = rewrite("SELECT id FROM t_order WHERE id = #{id}", param);
        assertTrue("期望改写为 " + expected + "，实际: " + sql, sql.contains(expected));
        assertTrue(!sql.contains("FROM t_order "));
    }

    @Test
    public void insertStatementAlsoRewritten() {
        Map<String, Object> param = new HashMap<String, Object>();
        param.put("orderId", "o-2");
        String expected = tableStrategy.shard(tableRule(), "o-2");

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
        String expected = tableStrategy.shard(tableRule(), "o-1");

        String sql = rewrite("SELECT u.id FROM t_user u LEFT JOIN t_order o ON u.id = o.id", param);
        assertTrue("join 中分片表应改写，实际: " + sql, sql.contains(expected));
        assertTrue("join 中非分片表不应改写，实际: " + sql, sql.contains("t_user"));
    }

    @Test
    public void missingShardingKeySilentlyKeepsLogicTable() {
        // 基线：键取不到时不改写也不报错，逻辑表名原样输出
        String sql = rewrite("SELECT id FROM t_order WHERE id = 1", new HashMap<String, Object>());
        assertTrue("基线行为：仍输出逻辑表名，实际: " + sql, sql.contains("t_order"));
        assertEquals(false, sql.contains("t_order_0") || sql.contains("t_order_1"));
    }
}
