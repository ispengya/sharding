package com.ispengya.sharding.integration;

import com.ispengya.sharding.algorithm.HashShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingContext;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.integration.mapper.PlainMapper;
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
 * 读写分离集成测试（真实 MySQL 三节点，server_id：master-1=1、master-2=2、slave=3）：
 * ① 分片组内主从：同一逻辑数据源按 route 分主从（ds_0 有从库 → 3，ds_1 无从库 → 降级 2）
 * ② 主库写、复制后从库读数据正确
 * ③ 活动事务内 route = SLAVE → 调用即报错
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApp.class)
public class ReadWriteSplitIntegrationTest {

    private static final String MASTER_1 = "jdbc:mysql://localhost:33061/db_1?serverTimezone=UTC&useSSL=false&characterEncoding=utf8";
    private static final String MASTER_2 = "jdbc:mysql://localhost:33062/db_2?serverTimezone=UTC&useSSL=false&characterEncoding=utf8";
    private static final String SLAVE = "jdbc:mysql://localhost:33063/db_1?serverTimezone=UTC&useSSL=false&characterEncoding=utf8";

    private static final int SERVER_ID_MASTER_1 = 1;
    private static final int SERVER_ID_MASTER_2 = 2;
    private static final int SERVER_ID_SLAVE = 3;

    @Autowired
    private PlainMapper plainMapper;
    @Autowired
    private TxService txService;

    private final HashShardingAlgorithm algorithm = new HashShardingAlgorithm();

    @Before
    public void setUpSchema() throws Exception {
        String ddl = "CREATE TABLE IF NOT EXISTS t_plain (id VARCHAR(64) PRIMARY KEY)";
        execute(MASTER_1, ddl);
        execute(MASTER_2, ddl);
        execute(MASTER_1, "DELETE FROM t_plain");
        execute(MASTER_2, "DELETE FROM t_plain");
    }

    @Test
    public void shardedGroupReadsItsOwnSlave() throws Exception {
        String ds0User = findValueForDb("ds_0");
        String ds1User = findValueForDb("ds_1");

        // ds_0 配了从库 → SLAVE 读到 slave 节点；默认 MASTER 读到 master-1
        assertEquals(SERVER_ID_SLAVE, plainMapper.slaveServerId(ds0User));
        assertEquals(SERVER_ID_MASTER_1, plainMapper.defaultServerId(ds0User));

        // ds_1 未配从库 → SLAVE 降级按 master 处理（文档已定，不报错）
        assertEquals(SERVER_ID_MASTER_2, plainMapper.slaveServerId(ds1User));
        assertEquals(SERVER_ID_MASTER_2, plainMapper.defaultServerId(ds1User));
    }

    @Test
    public void masterWriteVisibleOnSlaveReadAfterReplication() throws Exception {
        String ds0User = findValueForDb("ds_0");

        plainMapper.insertPlain(new TPlain("p-rw"));

        // 主库先可见
        assertEquals(1, count(MASTER_1, "t_plain", "p-rw"));
        // 轮询从库直到复制完成（异步复制，超时 10s）
        waitUntilReplicated("p-rw", 10_000);

        assertEquals("p-rw", plainMapper.findIdOnSlave("p-rw", ds0User));
    }

    @Test
    public void slaveRouteInsideTransactionThrows() {
        String ds0User = findValueForDb("ds_0");
        try {
            txService.readSlaveInTransaction(ds0User);
            fail("活动事务内 route = SLAVE 应当抛出 ShardingException");
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

    private void waitUntilReplicated(String id, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (count(SLAVE, "t_plain", id) >= 1) {
                return;
            }
            Thread.sleep(200);
        }
        fail("从库 " + timeoutMillis + "ms 内未复制到行: " + id);
    }

    private void execute(String url, String sql) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, "root", "root");
             Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private int count(String url, String table, String id) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, "root", "root");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE id = '" + id + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
