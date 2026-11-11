package com.ispengya.sharding.integration;

import com.ispengya.sharding.annotation.Mode;
import com.ispengya.sharding.config.ShardingProperties;
import com.ispengya.sharding.datasource.DataSourceFactory;
import com.ispengya.sharding.datasource.RouteResolver;
import com.ispengya.sharding.datasource.ShardingConnection;
import com.ispengya.sharding.datasource.ShardingDataSource;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.loadbalance.LoadBalances;
import com.ispengya.sharding.loadbalance.LoadBalancer;
import com.ispengya.sharding.loadbalance.RoundRobinLoadBalancer;
import com.ispengya.sharding.route.RouteContextHolder;
import com.ispengya.sharding.tx.LocalCommitStrategy;
import com.ispengya.sharding.tx.ThreePhaseCommitStrategy;
import com.ispengya.sharding.tx.TxCommitStrategy;
import com.ispengya.sharding.tx.TxContextHolder;
import com.ispengya.sharding.tx.coordinator.Coordinator;
import com.ispengya.sharding.tx.coordinator.TransactionLog;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 3PC 集成测试（真实 MySQL）：
 * ① 正常三阶段提交（Spring 全上下文，@TxMode(THREE_PC)）
 * ② 参与者 canCommit 拒绝 → 两分片全回滚、日志 ROLLED_BACK（手工装配注入）
 * ③ 参与者 canCommit 阶段超时 → 收敛为 abort、无 in-doubt 悬挂（手工装配注入 + 短超时）
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApp.class)
public class ThreePhaseIntegrationTest {

    private static final String MASTER_0_URL = "jdbc:mysql://localhost:33061/db_1?serverTimezone=UTC&useSSL=false&characterEncoding=utf8";
    private static final String MASTER_1_URL = "jdbc:mysql://localhost:33062/db_2?serverTimezone=UTC&useSSL=false&characterEncoding=utf8";
    private static final String TABLE = "t_3pc_test";

    private enum ProbeMode { NORMAL, FAIL, HANG }

    @Autowired
    private com.ispengya.sharding.integration.service.TxService txService;

    private final java.util.List<com.alibaba.druid.pool.DruidDataSource> openPools = new java.util.ArrayList<>();

    // ---------- ① Spring 全上下文 ----------

    @Test
    public void normalThreePhaseCommitPersistsBothRowsAndLogsCommitted() throws Exception {
        int logBefore = countCommittedLogRows();
        com.ispengya.sharding.integration.model.TOrder onDs0 =
                new com.ispengya.sharding.integration.model.TOrder("3pc-1", findValueForDb("ds_0"), "order-3pc-1");
        com.ispengya.sharding.integration.model.TOrder onDs1 =
                new com.ispengya.sharding.integration.model.TOrder("3pc-2", findValueForDb("ds_1"), "order-3pc-2");

        txService.insertTwoShardsThreePc(onDs0, onDs1);

        assertEquals(1, count(MASTER_0_URL, findTable(onDs0.getOrderId()), onDs0.getId()));
        assertEquals(1, count(MASTER_1_URL, findTable(onDs1.getOrderId()), onDs1.getId()));
        assertEquals(logBefore + 1, countCommittedLogRows());
    }

    // ---------- ②③ 手工装配 ----------

    private Map<String, DataSource> pools;
    private ShardingDataSource shardingDataSource;
    private TransactionLog transactionLog;
    private TxCommitStrategy threePcStrategy;

    @Before
    public void setUp() throws Exception {
        RouteContextHolder.clearDbHolder();
        RouteContextHolder.clearRoute();
        TxContextHolder.clearMode();
        TxContextHolder.clearCrossDatasourceWritten();
        org.springframework.transaction.support.TransactionSynchronizationManager
                .setActualTransactionActive(true);

        for (String url : new String[]{MASTER_0_URL, MASTER_1_URL}) {
            execute(url, "CREATE TABLE IF NOT EXISTS " + TABLE + " (id VARCHAR(64) PRIMARY KEY)");
            execute(url, "DELETE FROM " + TABLE);
            execute(url, "DELETE FROM t_order_0");
            execute(url, "DELETE FROM t_order_1");
        }
        buildAssembly(ProbeMode.NORMAL, ThreePhaseCommitStrategy.DEFAULT_STAGE_TIMEOUT_MILLIS);
    }

    @After
    public void tearDown() throws Exception {
        RouteContextHolder.clearDbHolder();
        RouteContextHolder.clearRoute();
        TxContextHolder.clearMode();
        TxContextHolder.clearCrossDatasourceWritten();
        org.springframework.transaction.support.TransactionSynchronizationManager
                .setActualTransactionActive(false);
        closeAllPools();
        for (String url : new String[]{MASTER_0_URL, MASTER_1_URL}) {
            try {
                execute(url, "DELETE FROM " + TABLE);
            } catch (Exception ignore) {
                // 遗留锁时交给下轮清理
            }
        }
    }

    @Test
    public void canCommitRejectionRollsBackBothShards() throws Exception {
        buildAssembly(ProbeMode.FAIL, ThreePhaseCommitStrategy.DEFAULT_STAGE_TIMEOUT_MILLIS);

        TxContextHolder.setMode(Mode.THREE_PC);
        ShardingConnection connection = (ShardingConnection) shardingDataSource.getConnection();
        String globalId;
        try {
            connection.setAutoCommit(false);
            writeTwoShards(connection);
            globalId = connection.getXaGlobalId();

            try {
                connection.commit();
                fail("canCommit 拒绝应当抛出异常");
            } catch (ShardingException expected) {
                assertTrue("实际异常: " + expected.getMessage(),
                        expected.getMessage().contains("canCommit"));
                assertTrue("实际异常: " + expected.getMessage(),
                        expected.getMessage().contains("已全部回滚"));
            }

            // 两分片全回滚、日志终态、本事务无 in-doubt 悬挂
            assertEquals(0, count(MASTER_0_URL, TABLE, "3pc-a"));
            assertEquals(0, count(MASTER_1_URL, TABLE, "3pc-b"));
            assertEquals(TransactionLog.STATE_ROLLED_BACK, queryLogState(globalId));
            assertTrue(!xaRecoverContains(globalId));
        } finally {
            connection.close();
        }
    }

    @Test
    public void canCommitStageTimeoutConvergesToAbortWithoutHanging() throws Exception {
        // 参与者探针挂起 + 短阶段超时（300ms）
        buildAssembly(ProbeMode.HANG, 300);

        TxContextHolder.setMode(Mode.THREE_PC);
        ShardingConnection connection = (ShardingConnection) shardingDataSource.getConnection();
        String globalId;
        try {
            connection.setAutoCommit(false);
            writeTwoShards(connection);
            globalId = connection.getXaGlobalId();

            try {
                connection.commit();
                fail("canCommit 超时应当抛出异常");
            } catch (ShardingException expected) {
                assertTrue("实际异常: " + expected.getMessage(),
                        expected.getMessage().contains("canCommit"));
                assertTrue("实际异常: " + expected.getMessage(),
                        expected.getMessage().contains("已全部回滚"));
            }

            // 收敛为 abort：数据回滚、日志 ROLLED_BACK、名册无悬挂
            assertEquals(0, count(MASTER_0_URL, TABLE, "3pc-a"));
            assertEquals(0, count(MASTER_1_URL, TABLE, "3pc-b"));
            assertEquals(TransactionLog.STATE_ROLLED_BACK, queryLogState(globalId));
            assertTrue("超时中止后不应留有 in-doubt 分支", !xaRecoverContains(globalId));
        } finally {
            connection.close();
        }
    }

    // ---------- 装配与注入 ----------

    private void buildAssembly(ProbeMode probeMode, long stageTimeoutMillis) {
        closeAllPools();
        ShardingProperties properties = new ShardingProperties();
        properties.setPrimary("ds_0");
        ShardingProperties.DataSourceGroup ds0 = new ShardingProperties.DataSourceGroup();
        ds0.setMaster(node(MASTER_0_URL));
        ShardingProperties.DataSourceGroup ds1 = new ShardingProperties.DataSourceGroup();
        ds1.setMaster(node(MASTER_1_URL));
        properties.getDataSources().put("ds_0", ds0);
        properties.getDataSources().put("ds_1", ds1);

        Map<String, LoadBalancer> loadBalances = LoadBalances.collect(
                Arrays.asList((LoadBalancer) new RoundRobinLoadBalancer()));
        RouteResolver resolver = new RouteResolver("ds_0", properties.getDataSources(), loadBalances);

        pools = new HashMap<>();
        pools.put(RouteResolver.masterKey("ds_0"), newPool("ds_0-master", ds0.getMaster()));
        DataSource ds1Pool = newPool("ds_1-master", ds1.getMaster());
        if (probeMode != ProbeMode.NORMAL) {
            ds1Pool = wrapProbe(ds1Pool, probeMode);
        }
        pools.put(RouteResolver.masterKey("ds_1"), ds1Pool);

        transactionLog = new TransactionLog(pools.get(RouteResolver.masterKey("ds_0")));
        threePcStrategy = new ThreePhaseCommitStrategy(new Coordinator(transactionLog), stageTimeoutMillis);

        Map<Mode, TxCommitStrategy> strategies = new HashMap<>();
        strategies.put(Mode.THREE_PC, threePcStrategy);
        strategies.put(Mode.XA, new com.ispengya.sharding.tx.TwoPhaseCommitStrategy(new Coordinator(transactionLog)));
        strategies.put(Mode.LOCAL, new LocalCommitStrategy());
        shardingDataSource = new ShardingDataSource(resolver, pools, strategies);
    }

    private void writeTwoShards(ShardingConnection connection) throws Exception {
        RouteContextHolder.setDbHolder("ds_0");
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("INSERT INTO " + TABLE + " (id) VALUES ('3pc-a')");
        }
        RouteContextHolder.setDbHolder("ds_1");
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("INSERT INTO " + TABLE + " (id) VALUES ('3pc-b')");
        }
    }

    private com.alibaba.druid.pool.DruidDataSource newPool(String name, ShardingProperties.NodeConfig config) {
        com.alibaba.druid.pool.DruidDataSource pool =
                (com.alibaba.druid.pool.DruidDataSource) DataSourceFactory.create(name, config);
        openPools.add(pool);
        return pool;
    }

    private void closeAllPools() {
        for (com.alibaba.druid.pool.DruidDataSource pool : openPools) {
            try {
                if (!pool.isClosed()) {
                    pool.close();
                }
            } catch (Exception ignore) {
                // 尽力关闭
            }
        }
        openPools.clear();
    }

    /**
     * 包装池：canCommit 探针（含 3pc-canCommit 标记的查询）按模式拒绝/挂起，其余真实
     */
    private DataSource wrapProbe(DataSource delegate, ProbeMode mode) {
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DataSource.class, XADataSource.class},
                (proxy, method, args) -> {
                    if ("getXAConnection".equals(method.getName())) {
                        XAConnection real = ((XADataSource) delegate).getXAConnection();
                        return wrapXaConnection(real, mode);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private XAConnection wrapXaConnection(XAConnection real, ProbeMode mode) {
        return (XAConnection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XAConnection.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        return wrapConnection(real.getConnection(), mode);
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private Connection wrapConnection(Connection real, ProbeMode mode) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("createStatement".equals(method.getName())) {
                        // 探针在 Statement 上执行 executeQuery，须在语句层拦截
                        return wrapStatement((Statement) method.invoke(real, args), mode);
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private Statement wrapStatement(Statement real, ProbeMode mode) {
        return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    if ("executeQuery".equals(method.getName()) && args != null
                            && args[0] instanceof String
                            && ((String) args[0]).contains("3pc-canCommit")) {
                        if (mode == ProbeMode.FAIL) {
                            throw new java.sql.SQLException("injected canCommit rejection");
                        }
                        // HANG：挂起到被阶段超时中断
                        try {
                            Thread.sleep(60_000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new java.sql.SQLException("probe interrupted", e);
                        }
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    // ---------- 断言与数据工具 ----------

    private String findValueForDb(String targetDs) {
        for (int i = 0; i < 500; i++) {
            String candidate = "user-" + i;
            String routed = new com.ispengya.sharding.algorithm.HashShardingAlgorithm().calculateDb(
                    com.ispengya.sharding.algorithm.ShardingContext.forDb(
                            candidate, Arrays.asList("ds_0", "ds_1"), null));
            if (targetDs.equals(routed)) {
                return candidate;
            }
        }
        throw new IllegalStateException("500 次内未找到路由到 " + targetDs + " 的用户值");
    }

    private String findTable(String orderId) {
        return new com.ispengya.sharding.algorithm.HashShardingAlgorithm().calculateTable(
                com.ispengya.sharding.algorithm.ShardingContext.forTable(orderId, "t_order", 2, null));
    }

    private int countCommittedLogRows() throws Exception {
        try (Connection conn = DriverManager.getConnection(MASTER_0_URL, "root", "root");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + TransactionLog.TABLE
                     + " WHERE state = 'COMMITTED' AND participants = 'ds_0#master,ds_1#master'")) {
            rs.next();
            return rs.getInt(1);
        } catch (java.sql.SQLException e) {
            if ("42S02".equals(e.getSQLState()) || e.getErrorCode() == 1146) {
                return 0;
            }
            throw e;
        }
    }

    private String queryLogState(String globalId) throws Exception {
        try (Connection conn = DriverManager.getConnection(MASTER_0_URL, "root", "root");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT state FROM " + TransactionLog.TABLE
                     + " WHERE global_id = '" + globalId + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private boolean xaRecoverContains(String globalId) throws Exception {
        for (String url : new String[]{MASTER_0_URL, MASTER_1_URL}) {
            try (Connection conn = DriverManager.getConnection(url, "root", "root");
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("XA RECOVER")) {
                while (rs.next()) {
                    String data = rs.getString("data");
                    int gtridLength = rs.getInt("gtrid_length");
                    if (globalId.equals(data.substring(0, gtridLength))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private ShardingProperties.NodeConfig node(String url) {
        ShardingProperties.NodeConfig node = new ShardingProperties.NodeConfig();
        node.setDriverClassName("com.mysql.cj.jdbc.Driver");
        node.setUrl(url);
        node.setUsername("root");
        node.setPassword("root");
        return node;
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
