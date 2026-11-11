package com.ispengya.sharding.integration;

import com.ispengya.sharding.algorithm.HashShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingContext;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.integration.mapper.OrderMapper;
import com.ispengya.sharding.integration.mapper.PlainMapper;
import com.ispengya.sharding.integration.model.TOrder;
import com.ispengya.sharding.integration.model.TPlain;
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
 * 规则行为集成测试（真实 MySQL）：
 * ① 无规则表直通 primary ② 规则表与无规则表混合场景 ③ 分库键取不到时调用即报错
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApp.class)
public class ShardingRuleIntegrationTest {

    private static final String URL_0 = "jdbc:mysql://localhost:33061/db_1?serverTimezone=UTC&useSSL=false&characterEncoding=utf8";
    private static final String URL_1 = "jdbc:mysql://localhost:33062/db_2?serverTimezone=UTC&useSSL=false&characterEncoding=utf8";
    private static final String USER = "root";
    private static final String PASSWORD = "root";

    private static final String DDL_PLAIN =
            "CREATE TABLE IF NOT EXISTS t_plain (id VARCHAR(64) PRIMARY KEY, note VARCHAR(255))";

    @Autowired
    private PlainMapper plainMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private TxService txService;

    private final HashShardingAlgorithm algorithm = new HashShardingAlgorithm();

    @Before
    public void setUpSchema() throws Exception {
        for (String url : new String[]{URL_0, URL_1}) {
            execute(url, DDL_PLAIN);
            execute(url, "DELETE FROM t_plain");
        }
    }

    @Test
    public void noRuleTableGoesToPrimary() throws Exception {
        plainMapper.insertPlain(new TPlain("p-1"));

        assertEquals(1, count(URL_0, "t_plain"));
        assertEquals(0, count(URL_1, "t_plain"));
    }

    @Test
    public void mixedRuleAndNoRuleTablesEachLandCorrectly() throws Exception {
        // 无规则表 → primary（ds_0）
        plainMapper.insertPlain(new TPlain("p-mixed"));

        // 规则表 → 按 userId 路由到 ds_1
        String ds1User = findValueForDb("ds_1");
        String table = algorithm.calculateTable(ShardingContext.forTable("order-mixed", "t_order", 2, null));
        txService.insertOk(new TOrder("m-1", ds1User, "order-mixed"));

        assertEquals(1, count(URL_0, "t_plain"));
        assertEquals(1, count(URL_1, table));
    }

    @Test
    public void unannotatedShardedSqlRoutesAutomatically() throws Exception {
        // 方案 B 核心行为：不标 @DBRouter，SQL 碰 t_order 也按表规则路由到正确分片
        String ds1User = findValueForDb("ds_1");
        String table = algorithm.calculateTable(ShardingContext.forTable("order-auto", "t_order", 2, null));
        execute(URL_0, "DELETE FROM " + table + " WHERE id = 'm-auto'");
        execute(URL_1, "DELETE FROM " + table + " WHERE id = 'm-auto'");

        orderMapper.insertAutoRouted(new TOrder("m-auto", ds1User, "order-auto"));

        assertEquals(1, countWhere(URL_1, table, "m-auto"));
        assertEquals(0, countWhere(URL_0, table, "m-auto"));
    }

    @Test
    public void missingShardingKeyThrowsAtCallTime() {
        // TPlain 无 userId 字段 → 分库键提取失败 → 调用即抛异常（不静默）
        try {
            plainMapper.routedByPlainWithoutShardingKey(new TPlain("p-x"));
            fail("应当抛出 ShardingException");
        } catch (ShardingException expected) {
            // 期望的校验错误
        }
    }

    // ---------- helpers ----------

    private String findValueForDb(String targetDs) {
        for (int i = 0; i < 200; i++) {
            String candidate = "user-" + i;
            String routed = algorithm.calculateDb(
                    ShardingContext.forDb(candidate, Arrays.asList("ds_0", "ds_1"), null));
            if (targetDs.equals(routed)) {
                return candidate;
            }
        }
        throw new IllegalStateException("200 次内未找到路由到 " + targetDs + " 的用户值");
    }

    private void execute(String url, String sql) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private int count(String url, String table) throws Exception {
        return countWhere(url, table, null);
    }

    private int countWhere(String url, String table, String id) throws Exception {
        String sql = id == null ? "SELECT COUNT(*) FROM " + table
                : "SELECT COUNT(*) FROM " + table + " WHERE id = '" + id + "'";
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
