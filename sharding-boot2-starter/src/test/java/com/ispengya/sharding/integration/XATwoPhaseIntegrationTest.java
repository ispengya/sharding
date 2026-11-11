package com.ispengya.sharding.integration;

import com.ispengya.sharding.algorithm.HashShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingContext;
import com.ispengya.sharding.integration.model.TOrder;
import com.ispengya.sharding.integration.service.TxService;
import com.ispengya.sharding.tx.coordinator.TransactionLog;
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
import static org.junit.Assert.assertTrue;

/**
 * 2PC/XA 集成测试①（真实 MySQL，Spring 全上下文）：
 * 跨分片 @TxMode(XA) → prepare 全过 → 两分片同时提交、事务日志落 COMMITTED、日志表由框架新建。
 * （② prepare 失败全回滚、③ 崩溃恢复 见 XARecoveryIntegrationTest 手工装配用例）
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApp.class)
public class XATwoPhaseIntegrationTest {

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
    public void crossShardXaCommitPersistsBothRowsAndLogsCommitted() throws Exception {
        TOrder onDs0 = new TOrder("xa-1", findValueForDb("ds_0"), "order-xa-1");
        TOrder onDs1 = new TOrder("xa-2", findValueForDb("ds_1"), "order-xa-2");
        int logBefore = countCommittedLogRows();

        txService.insertTwoShardsXa(onDs0, onDs1);

        // 两分片同时可见
        assertEquals(1, count(findTable(onDs0.getOrderId()), URL_0, onDs0.getId()));
        assertEquals(1, count(findTable(onDs1.getOrderId()), URL_1, onDs1.getId()));

        // 事务日志由框架新建并推进到 COMMITTED
        assertTrue("日志表应由框架自动创建", tableExists(URL_0, TransactionLog.TABLE));
        assertEquals("应新增一条 COMMITTED 日志", logBefore + 1, countCommittedLogRows());
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

    private int countCommittedLogRows() throws Exception {
        try (Connection conn = DriverManager.getConnection(URL_0, USER, PASSWORD);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + TransactionLog.TABLE
                     + " WHERE state = 'COMMITTED' AND participants = 'ds_0#master,ds_1#master'")) {
            rs.next();
            return rs.getInt(1);
        } catch (java.sql.SQLException e) {
            // 日志表由首次 XA 使用时创建，此前计数为 0
            if ("42S02".equals(e.getSQLState()) || e.getErrorCode() == 1146) {
                return 0;
            }
            throw e;
        }
    }

    private boolean tableExists(String url, String table) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW TABLES LIKE '" + table + "'")) {
            return rs.next();
        }
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
