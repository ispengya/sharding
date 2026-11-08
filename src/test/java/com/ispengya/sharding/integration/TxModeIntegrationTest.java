package com.ispengya.sharding.integration;

import com.ispengya.sharding.algorithm.HashShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingContext;
import com.ispengya.sharding.exception.ShardingException;
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
 * @TxMode 使用规则集成测试（真实 MySQL，usage.md 第 4 节逐条验证）：
 * ⑤ 跨分片写未标 @TxMode → 调用时报错、事务整体回滚
 * ⑥ 跨数据源只读无需标注
 * ⑦ 外层已跨分片写 → 内层 REQUIRES_NEW 写入即报错
 * ⑧ NESTED 内层写第二个数据源即报错
 * （跨分片 LOCAL 提交/回滚、单分片免标注、REQUIRES_NEW 单分片合法场景见
 *   MultiDatasourceTxIntegrationTest / ShardingTxIntegrationTest）
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApp.class)
public class TxModeIntegrationTest {

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
    public void missingTxModeFailsAtSecondShardWriteAndRollsBackBoth() throws Exception {
        TOrder onDs0 = new TOrder("tx-1", findValueForDb("ds_0"), "order-tx-1");
        TOrder onDs1 = new TOrder("tx-2", findValueForDb("ds_1"), "order-tx-2");

        try {
            txService.insertTwoShardsWithoutTxMode(onDs0, onDs1);
            fail("跨分片写未标 @TxMode 应当抛出异常");
        } catch (Exception expected) {
            assertRootCauseSharding(expected, "@TxMode");
        }

        // 事务整体回滚：两个数据源均无数据
        assertEquals(0, count(findTable(onDs0.getOrderId()), URL_0, onDs0.getId()));
        assertEquals(0, count(findTable(onDs1.getOrderId()), URL_1, onDs1.getId()));
    }

    @Test
    public void crossDatasourceReadOnlyNeedsNoTxMode() throws Exception {
        // 只读跨两个数据源：无写入、无标注 → 正常完成
        txService.readAcrossShards(findValueForDb("ds_0"), findValueForDb("ds_1"), "order-ro");
        // 不抛异常即通过
    }

    @Test
    public void requiresNewBannedWhenOuterAlreadyWroteTwoShards() throws Exception {
        TOrder first = new TOrder("tx-3", findValueForDb("ds_0"), "order-tx-3");
        TOrder second = new TOrder("tx-4", findValueForDb("ds_1"), "order-tx-4");
        TOrder inner = new TOrder("tx-5", findValueForDb("ds_1"), "order-tx-5");

        try {
            txService.insertTwoShardsThenInnerRequiresNew(first, second, inner);
            fail("外层已跨分片写时内层 REQUIRES_NEW 应当抛出异常");
        } catch (Exception expected) {
            assertRootCauseSharding(expected, "REQUIRES_NEW");
        }

        // 外层回滚、内层未写入
        assertEquals(0, count(findTable(first.getOrderId()), URL_0, first.getId()));
        assertEquals(0, count(findTable(second.getOrderId()), URL_1, second.getId()));
        assertEquals(0, count(findTable(inner.getOrderId()), URL_1, inner.getId()));
    }

    @Test
    public void nestedWriteToSecondShardBanned() throws Exception {
        TOrder first = new TOrder("tx-6", findValueForDb("ds_0"), "order-tx-6");
        TOrder nested = new TOrder("tx-7", findValueForDb("ds_1"), "order-tx-7");

        try {
            txService.insertOneShardThenNestedSecondShard(first, nested);
            fail("NESTED 内层写第二个数据源应当抛出异常");
        } catch (Exception expected) {
            assertRootCauseSharding(expected, "NESTED");
        }

        assertEquals(0, count(findTable(first.getOrderId()), URL_0, first.getId()));
        assertEquals(0, count(findTable(nested.getOrderId()), URL_1, nested.getId()));
    }

    /**
     * 执行期异常经 MyBatis 包装（MyBatisSystemException → PersistenceException → 根因），
     * 沿 cause 链断言根因是含指定内容的 ShardingException；切面期异常则原样抛出
     */
    private static void assertRootCauseSharding(Throwable e, String messagePart) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof ShardingException) {
                org.junit.Assert.assertNotNull(cause.getMessage());
                org.junit.Assert.assertTrue("异常信息应包含 [" + messagePart + "]，实际: " + cause.getMessage(),
                        cause.getMessage().contains(messagePart));
                return;
            }
            cause = cause.getCause();
        }
        fail("异常链中未找到 ShardingException: " + e);
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
