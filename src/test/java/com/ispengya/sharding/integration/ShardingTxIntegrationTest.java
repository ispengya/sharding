package com.ispengya.sharding.integration;

import com.ispengya.sharding.integration.mapper.OrderMapper;
import com.ispengya.sharding.integration.model.TOrder;
import com.ispengya.sharding.integration.service.TxService;
import com.ispengya.sharding.rule.DBShardingRule;
import com.ispengya.sharding.rule.TableShardingRule;
import com.ispengya.sharding.algorithm.HashCodeDBShardingStrategy;
import com.ispengya.sharding.algorithm.HashCodeTableShardingStrategy;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * 行为基线集成测试：分库路由、分表改写、事务提交/回滚。
 * 打真实 MySQL（docker-compose 三节点：master-1:33061 / master-2:33062），
 * 用 bash scripts/test.sh 一键起环境并执行；阶段 1/2 重构后本组断言不得变化。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApp.class)
public class ShardingTxIntegrationTest {

    private static final String URL_0 = "jdbc:mysql://localhost:33061/db_1?serverTimezone=UTC&useSSL=false&characterEncoding=utf8";
    private static final String URL_1 = "jdbc:mysql://localhost:33062/db_2?serverTimezone=UTC&useSSL=false&characterEncoding=utf8";
    private static final String USER = "root";
    private static final String PASSWORD = "root";

    private static final String DDL =
            "CREATE TABLE IF NOT EXISTS t_order_0 (id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64), order_id VARCHAR(64))";

    @Autowired
    private TxService txService;
    @Autowired
    private OrderMapper orderMapper;

    private final HashCodeDBShardingStrategy dbStrategy = new HashCodeDBShardingStrategy();
    private final HashCodeTableShardingStrategy tableStrategy = new HashCodeTableShardingStrategy();

    @Before
    public void setUpSchema() throws Exception {
        for (String url : new String[]{URL_0, URL_1}) {
            execute(url, DDL);
            execute(url, "CREATE TABLE IF NOT EXISTS t_order_1 (id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64), order_id VARCHAR(64))");
            execute(url, "DELETE FROM t_order_0");
            execute(url, "DELETE FROM t_order_1");
        }
    }

    @Test
    public void commitRoutesRowsToExpectedShardAndPhysicalTable() throws Exception {
        String ds0User = findValueForDb("ds_0");
        String ds1User = findValueForDb("ds_1");
        String t0Order = findValueForTable("t_order_0");
        String t1Order = findValueForTable("t_order_1");

        TOrder a = new TOrder("a-1", ds0User, t0Order);
        TOrder b = new TOrder("b-1", ds1User, t1Order);

        txService.insertOk(a);
        txService.insertOk(b);

        // 分库：各自落预期分片
        assertEquals(1, count(URL_0, "t_order_0") + count(URL_0, "t_order_1"));
        assertEquals(1, count(URL_1, "t_order_0") + count(URL_1, "t_order_1"));
        // 分表：行落在预期物理表
        assertEquals(1, count(URL_0, "t_order_0"));
        assertEquals(1, count(URL_1, "t_order_1"));

        // 读回：SELECT 同样走分库路由 + 分表改写
        assertEquals("a-1", orderMapper.findId(a));
        assertEquals("b-1", orderMapper.findId(b));
    }

    @Test
    public void rollbackRevertsInsertOnSingleShard() throws Exception {
        String ds0User = findValueForDb("ds_0");
        String t0Order = findValueForTable("t_order_0");
        TOrder c = new TOrder("c-1", ds0User, t0Order);

        try {
            txService.insertAndBoom(c);
            fail("应当抛出异常触发回滚");
        } catch (IllegalStateException expected) {
            // 期望的回滚触发异常
        }

        assertEquals(0, count(URL_0, "t_order_0") + count(URL_0, "t_order_1"));
        assertEquals(0, count(URL_1, "t_order_0") + count(URL_1, "t_order_1"));
    }

    // ---------- helpers ----------

    private String findValueForDb(String targetDs) {
        DBShardingRule rule = new DBShardingRule();
        rule.setDbCount(2);
        for (int i = 0; i < 200; i++) {
            String candidate = "user-" + i;
            if (targetDs.equals(dbStrategy.shard(rule, candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException("200 次内未找到路由到 " + targetDs + " 的用户值");
    }

    private String findValueForTable(String targetTable) {
        TableShardingRule rule = new TableShardingRule();
        rule.setLogicTableName("t_order");
        rule.setTableCount(2);
        for (int i = 0; i < 200; i++) {
            String candidate = "order-" + i;
            if (targetTable.equals(tableStrategy.shard(rule, candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException("200 次内未找到路由到 " + targetTable + " 的订单值");
    }

    private void execute(String url, String sql) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private int count(String url, String table) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
