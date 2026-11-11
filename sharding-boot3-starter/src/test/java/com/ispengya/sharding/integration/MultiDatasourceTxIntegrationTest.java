package com.ispengya.sharding.integration;

import com.ispengya.sharding.algorithm.HashShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingContext;
import com.ispengya.sharding.integration.model.TOrder;
import com.ispengya.sharding.integration.service.TxService;
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
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * 跨数据源事务集成测试（真实 MySQL，阶段 4）：
 * ① 一个事务写两个数据源 → 循环提交两行都落库
 * ② 跨分片写后回滚 → 两个数据源都撤销
 * ③ 外层回滚不影响 REQUIRES_NEW 内层已提交的数据（逻辑连接隔离）
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApp.class)
public class MultiDatasourceTxIntegrationTest {

    private static final String URL_0 = "jdbc:mysql://localhost:33061/db_1?serverTimezone=UTC&useSSL=false&characterEncoding=utf8";
    private static final String URL_1 = "jdbc:mysql://localhost:33062/db_2?serverTimezone=UTC&useSSL=false&characterEncoding=utf8";
    private static final String USER = "root";
    private static final String PASSWORD = "root";

    @Autowired
    private TxService txService;

    private final HashShardingAlgorithm algorithm = new HashShardingAlgorithm();

    @Before
    public void clean() throws Exception {
        for (String url : new String[]{URL_0, URL_1}) {
            execute(url, "DELETE FROM t_order_0");
            execute(url, "DELETE FROM t_order_1");
        }
    }

    @Test
    public void crossShardCommitPersistsBothRows() throws Exception {
        TOrder onDs0 = new TOrder("m4-1", findValueForDb("ds_0"), "order-m4-1");
        TOrder onDs1 = new TOrder("m4-2", findValueForDb("ds_1"), "order-m4-2");

        txService.insertTwoShards(onDs0, onDs1);

        assertEquals(1, count(findTable(onDs0.getOrderId()), URL_0, onDs0.getId()));
        assertEquals(1, count(findTable(onDs1.getOrderId()), URL_1, onDs1.getId()));
    }

    @Test
    public void crossShardRollbackRevertsBothRows() throws Exception {
        TOrder onDs0 = new TOrder("m4-3", findValueForDb("ds_0"), "order-m4-3");
        TOrder onDs1 = new TOrder("m4-4", findValueForDb("ds_1"), "order-m4-4");

        try {
            txService.insertTwoShardsThenBoom(onDs0, onDs1);
            fail("应当抛出 IllegalStateException");
        } catch (IllegalStateException expected) {
            // 期望的回滚触发异常
        }

        assertEquals(0, count(findTable(onDs0.getOrderId()), URL_0, onDs0.getId()));
        assertEquals(0, count(findTable(onDs1.getOrderId()), URL_1, onDs1.getId()));
    }

    @Test
    public void requiresNewCommitsSurviveOuterRollback() throws Exception {
        TOrder outer = new TOrder("m4-5", findValueForDb("ds_0"), "order-m4-5");
        TOrder inner = new TOrder("m4-6", findValueForDb("ds_1"), "order-m4-6");

        try {
            txService.insertOuterThenInnerRequiresNew(outer, inner);
            fail("应当抛出 IllegalStateException");
        } catch (IllegalStateException expected) {
            // 外层回滚触发
        }

        // 外层（ds_0）回滚消失；内层 REQUIRES_NEW（ds_1）独立提交保留
        assertEquals(0, count(findTable(outer.getOrderId()), URL_0, outer.getId()));
        assertEquals(1, count(findTable(inner.getOrderId()), URL_1, inner.getId()));
    }

    // ---------- helpers ----------

    private String findValueForDb(String targetDs) {
        for (int i = 0; i < 500; i++) {
            String candidate = "user-" + i;
            String routed = algorithm.calculateDb(
                    ShardingContext.forDb(candidate, Arrays.asList("ds_0", "ds_1"), null));
            if (targetDs.equals(routed)) {
                return candidate;
            }
        }
        throw new IllegalStateException("500 次内未找到路由到 " + targetDs + " 的用户值");
    }

    private String findTable(String orderId) {
        return algorithm.calculateTable(ShardingContext.forTable(orderId, "t_order", 2, null));
    }

    private void execute(String url, String sql) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private int count(String table, String url, String id) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE id = '" + id + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
