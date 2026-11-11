package com.ispengya.sharding.integration;

import com.alibaba.druid.pool.DruidDataSource;
import com.ispengya.sharding.annotation.Mode;
import com.ispengya.sharding.config.ShardingProperties;
import com.ispengya.sharding.datasource.DataSourceFactory;
import com.ispengya.sharding.datasource.RouteResolver;
import com.ispengya.sharding.datasource.ShardingConnection;
import com.ispengya.sharding.datasource.ShardingDataSource;
import com.ispengya.sharding.datasource.XaBranch;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.loadbalance.LoadBalances;
import com.ispengya.sharding.loadbalance.LoadBalancer;
import com.ispengya.sharding.loadbalance.RoundRobinLoadBalancer;
import com.ispengya.sharding.route.RouteContextHolder;
import com.ispengya.sharding.tx.LocalCommitStrategy;
import com.ispengya.sharding.tx.TwoPhaseCommitStrategy;
import com.ispengya.sharding.tx.TxCommitStrategy;
import com.ispengya.sharding.tx.TxContextHolder;
import com.ispengya.sharding.tx.coordinator.Coordinator;
import com.ispengya.sharding.tx.coordinator.TransactionLog;
import com.ispengya.sharding.tx.coordinator.TransactionRecoveryListener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 2PC/XA 集成测试②③（真实 MySQL，手工装配，不经 Spring 上下文）：
 * ② 注入某分片 prepare 失败 → 两个分片全部回滚、日志落 ROLLED_BACK
 * ③ 模拟协调者宕机：prepare 完成后直接关闭池（会话死亡，prepared XA 以 in-doubt
 *    留存 MySQL）→ XA RECOVER 验证现场 → 全新装配执行启动恢复 → 数据提交、日志 COMMITTED
 */
public class XARecoveryIntegrationTest {

    private static final String MASTER_0_URL = "jdbc:mysql://localhost:33061/db_1?serverTimezone=UTC&useSSL=false&characterEncoding=utf8";
    private static final String MASTER_1_URL = "jdbc:mysql://localhost:33062/db_2?serverTimezone=UTC&useSSL=false&characterEncoding=utf8";
    private static final String TABLE = "t_xa_test";

    private final java.util.List<DruidDataSource> openPools = new java.util.ArrayList<>();

    private Map<String, DataSource> pools;
    private ShardingDataSource shardingDataSource;
    private TransactionLog transactionLog;
    private TxCommitStrategy xaStrategy;

    @Before
    public void setUp() throws Exception {
        RouteContextHolder.clearDbHolder();
        RouteContextHolder.clearRoute();
        TxContextHolder.clearMode();
        TxContextHolder.clearCrossDatasourceWritten();
        // 手工装配没有 Spring 事务拦截器：显式置位"活动事务"，
        // 使 isXaBranchNeeded() 成立（与 doBegin 的置位等价）
        org.springframework.transaction.support.TransactionSynchronizationManager
                .setActualTransactionActive(true);

        resolveLeftoverInDoubt();
        for (String url : new String[]{MASTER_0_URL, MASTER_1_URL}) {
            execute(url, "CREATE TABLE IF NOT EXISTS " + TABLE + " (id VARCHAR(64) PRIMARY KEY)");
            execute(url, "DELETE FROM " + TABLE);
        }
        buildAssembly(false);
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
            } catch (Exception e) {
                // 上轮遗留锁时留给下一轮 setUp 的 in-doubt 清理
                System.err.println("tearDown 清理数据失败（将在下次 setUp 清理 in-doubt 后重试）: " + e.getMessage());
            }
        }
    }

    // ---------- ② prepare 失败 → 全部回滚 ----------

    @Test
    public void prepareFailureOnOneShardRollsBackAllAndLogsRolledBack() throws Exception {
        // ds_1 的 XA prepare 必败（包装池注入），其余真实
        buildAssembly(true);
        int logBefore = countLogState(TransactionLog.STATE_ROLLED_BACK);

        TxContextHolder.setMode(Mode.XA);
        ShardingConnection connection = (ShardingConnection) shardingDataSource.getConnection();
        try {
            connection.setAutoCommit(false);

            RouteContextHolder.setDbHolder("ds_0");
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("INSERT INTO " + TABLE + " (id) VALUES ('xa2-ok')");
            }
            RouteContextHolder.setDbHolder("ds_1");
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("INSERT INTO " + TABLE + " (id) VALUES ('xa2-fail')");
            }

            try {
                connection.commit();
                fail("prepare 失败应当抛出异常");
            } catch (ShardingException expected) {
                Throwable root = expected;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                String detail = expected.getMessage() + " | root=" + root.getClass().getSimpleName()
                        + ":" + root.getMessage();
                assertTrue("实际异常: " + detail, expected.getMessage().contains("prepare 失败"));
                assertTrue("实际异常: " + detail, expected.getMessage().contains("ds_1#master"));
            }

            // 两个分片都回滚：ds_0 已 PREPARED 的被补回滚，ds_1 从未提交
            assertEquals(0, count(MASTER_0_URL, "xa2-ok"));
            assertEquals(0, count(MASTER_1_URL, "xa2-fail"));
            // 日志推进到 ROLLED_BACK
            assertEquals(logBefore + 1, countLogState(TransactionLog.STATE_ROLLED_BACK));
        } finally {
            connection.close();
        }
    }

    // ---------- ②' 超时双写竞态 → 诊断明细不被收尾写抹掉 ----------

    @Test
    public void rolledBackDetailSurvivesFollowUpBlankWrite() throws Exception {
        // 复刻 3PC 阶段超时路径的双写：被中断的 stage 线程先写 ROLLED_BACK（带诊断明细），
        // 主线程兜底回滚再写一次 ROLLED_BACK（收尾不带明细）——收尾写不得覆盖诊断明细
        buildAssembly(false);
        String globalId = "gid-detail-keep-0001";
        execute(MASTER_0_URL, "DELETE FROM " + TransactionLog.TABLE + " WHERE global_id = '" + globalId + "'");
        TransactionLog log = new TransactionLog(pools.get(RouteResolver.masterKey("ds_0")));

        log.record(globalId, TransactionLog.STATE_BEGIN, Arrays.asList("ds_0#master"), null);
        // stage 线程的失败写：携带"哪个节点 prepare 失败"的诊断明细
        log.updateState(globalId, TransactionLog.STATE_ROLLED_BACK, "prepare 失败节点=ds_0#master，全部回滚");
        // 主线程兜底回滚的收尾写（detail = null）：语义上"无新信息"，应保留旧明细
        log.updateState(globalId, TransactionLog.STATE_ROLLED_BACK, null);

        assertEquals(TransactionLog.STATE_ROLLED_BACK, queryLogState(globalId));
        assertEquals("prepare 失败节点=ds_0#master，全部回滚", queryLogDetail(globalId));
    }

    // ---------- ③ 协调者宕机 → 启动恢复 ----------

    @Test
    public void preparedTransactionSurvivesPoolCloseAndRecoveryCommitsIt() throws Exception {
        String globalId;
        // 分支会话 id（prepare 后无法再在该会话执行普通语句，需提前记录）
        java.util.List<String[]> sessionIds = new java.util.ArrayList<>();
        ShardingConnection abandoned = (ShardingConnection) shardingDataSource.getConnection();
        try {
            // 走到 PREPARED（承诺已落盘）
            TxContextHolder.setMode(Mode.XA);
            abandoned.setAutoCommit(false);
            RouteContextHolder.setDbHolder("ds_0");
            try (Statement st = abandoned.createStatement()) {
                st.executeUpdate("INSERT INTO " + TABLE + " (id) VALUES ('rec-1')");
            }
            RouteContextHolder.setDbHolder("ds_1");
            try (Statement st = abandoned.createStatement()) {
                st.executeUpdate("INSERT INTO " + TABLE + " (id) VALUES ('rec-2')");
            }
            for (XaBranch branch : abandoned.getXaBranches().values()) {
                String url = branch.getNodeKey().startsWith("ds_0") ? MASTER_0_URL : MASTER_1_URL;
                try (Statement st = branch.getConnection().createStatement();
                     ResultSet rs = st.executeQuery("SELECT CONNECTION_ID()")) {
                    rs.next();
                    sessionIds.add(new String[]{url, String.valueOf(rs.getLong(1))});
                }
            }
            xaStrategy.prepare(abandoned);
            globalId = abandoned.getXaGlobalId();
            assertNotNull(globalId);
            assertEquals(TransactionLog.STATE_PREPARED, queryLogState(globalId));
        } finally {
            RouteContextHolder.clearDbHolder();
            TxContextHolder.clearMode();
        }

        // 模拟进程死亡：关闭全部池 + 强杀分支会话（等价于 kill -9 掉进程），
        // 逻辑连接弃而不关
        closeAllPools();
        for (String[] session : sessionIds) {
            killQuietly(session[0], session[1]);
        }

        // prepared XA 以 in-doubt 留存于 MySQL（与连接无关）——XA RECOVER 名册可查
        assertTrue("会话被杀后分支应以 in-doubt 留存", xaRecoverContains(globalId));

        // —— 重启：全新池 + 全新日志 + 启动恢复 ——
        buildAssembly(false);
        TransactionLog freshLog = new TransactionLog(pools.get(RouteResolver.masterKey("ds_0")));
        new TransactionRecoveryListener(freshLog, shardingDataSource).onApplicationEvent(null);

        // 兑现承诺：两行都提交、日志 COMMITTED
        assertEquals(1, count(MASTER_0_URL, "rec-1"));
        assertEquals(1, count(MASTER_1_URL, "rec-2"));
        assertEquals(TransactionLog.STATE_COMMITTED, queryLogState(globalId));
        // in-doubt 已清空
        assertTrue("恢复后 in-doubt 应清空", !xaRecoverContains(globalId));
    }

    // ---------- 装配 ----------

    private void buildAssembly(boolean failPrepareOnDs1) {
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
        if (failPrepareOnDs1) {
            ds1Pool = wrapWithFailingPrepare(ds1Pool);
        }
        pools.put(RouteResolver.masterKey("ds_1"), ds1Pool);

        DataSource primaryPool = pools.get(RouteResolver.masterKey("ds_0"));
        transactionLog = new TransactionLog(primaryPool);
        xaStrategy = new TwoPhaseCommitStrategy(new Coordinator(transactionLog));

        Map<Mode, TxCommitStrategy> strategies = new HashMap<>();
        strategies.put(Mode.XA, xaStrategy);
        strategies.put(Mode.LOCAL, new LocalCommitStrategy());
        shardingDataSource = new ShardingDataSource(resolver, pools, strategies);
    }

    private DataSource newPool(String name, ShardingProperties.NodeConfig config) {
        DruidDataSource pool = (DruidDataSource) DataSourceFactory.create(name, config);
        openPools.add(pool);
        return pool;
    }

    private void closeAllPools() {
        for (DruidDataSource pool : openPools) {
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

    // ---------- 注入与断言工具 ----------

    /**
     * 清理上一轮遗留的本框架 in-doubt 分支（formatID 匹配），避免行锁拖死后续用例。
     * MySQL 5.7 的 XA RECOVER 列为 formatID/gtrid_length/bqual_length/data（data = 原始拼接）
     */
    private void resolveLeftoverInDoubt() throws Exception {
        for (String url : new String[]{MASTER_0_URL, MASTER_1_URL}) {
            // 先收集再执行：同一 Statement 上 ResultSet 未关时不能执行新语句
            java.util.List<String> rollbackSqls = new java.util.ArrayList<>();
            try (Connection conn = DriverManager.getConnection(url, "root", "root");
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("XA RECOVER")) {
                while (rs.next()) {
                    int formatId = rs.getInt("formatID");
                    if (formatId != com.ispengya.sharding.tx.coordinator.XidSupport.FORMAT_ID) {
                        continue;
                    }
                    String data = rs.getString("data");
                    int gtridLength = rs.getInt("gtrid_length");
                    int bqualLength = rs.getInt("bqual_length");
                    String gtrid = data.substring(0, gtridLength);
                    String bqual = data.substring(gtridLength, gtridLength + bqualLength);
                    rollbackSqls.add("XA ROLLBACK '" + gtrid + "', '" + bqual + "', " + formatId);
                }
            }
            if (rollbackSqls.isEmpty()) {
                continue;
            }
            try (Connection conn = DriverManager.getConnection(url, "root", "root");
                 Statement st = conn.createStatement()) {
                for (String sql : rollbackSqls) {
                    try {
                        st.execute(sql);
                    } catch (Exception e) {
                        // 已被并发处理则跳过
                    }
                }
            }
        }
    }

    private void killQuietly(String url, String connectionId) {
        try {
            execute(url, "KILL " + connectionId);
        } catch (Exception ignore) {
            // 会话已不存在
        }
    }

    /**
     * 包装池：XA 连接的 prepare 必败，其余 XA 操作与 SQL 全部真实委托
     */
    private DataSource wrapWithFailingPrepare(DataSource delegate) {
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DataSource.class, XADataSource.class},
                (proxy, method, args) -> {
                    if ("getXAConnection".equals(method.getName())) {
                        XAConnection real = ((XADataSource) delegate).getXAConnection();
                        return wrapXaConnection(real);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private XAConnection wrapXaConnection(XAConnection real) {
        return (XAConnection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XAConnection.class},
                (proxy, method, args) -> {
                    if ("getXAResource".equals(method.getName())) {
                        return wrapXaResource(real.getXAResource());
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private XAResource wrapXaResource(XAResource real) {
        return (XAResource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XAResource.class},
                (proxy, method, args) -> {
                    if ("prepare".equals(method.getName())) {
                        throw new XAException("injected prepare failure");
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private boolean xaRecoverContains(String globalId) throws Exception {
        for (String url : new String[]{MASTER_0_URL, MASTER_1_URL}) {
            try (Connection conn = DriverManager.getConnection(url, "root", "root");
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("XA RECOVER")) {
                while (rs.next()) {
                    String data = rs.getString("data");
                    int gtridLength = rs.getInt("gtrid_length");
                    String gtrid = data.substring(0, gtridLength);
                    if (globalId.equals(gtrid)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String queryLogState(String globalId) throws Exception {
        try (Connection conn = DriverManager.getConnection(MASTER_0_URL, "root", "root");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT state FROM " + TransactionLog.TABLE
                     + " WHERE global_id = '" + globalId + "'")) {
            if (rs.next()) {
                return rs.getString(1);
            }
            return null;
        }
    }

    private String queryLogDetail(String globalId) throws Exception {
        try (Connection conn = DriverManager.getConnection(MASTER_0_URL, "root", "root");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT detail FROM " + TransactionLog.TABLE
                     + " WHERE global_id = '" + globalId + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private int countLogState(String state) throws Exception {
        try (Connection conn = DriverManager.getConnection(MASTER_0_URL, "root", "root");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + TransactionLog.TABLE
                     + " WHERE state = '" + state + "'")) {
            rs.next();
            return rs.getInt(1);
        } catch (java.sql.SQLException e) {
            if ("42S02".equals(e.getSQLState()) || e.getErrorCode() == 1146) {
                return 0;
            }
            throw e;
        }
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

    private int count(String url, String id) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, "root", "root");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + TABLE + " WHERE id = '" + id + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
