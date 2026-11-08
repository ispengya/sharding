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
import com.ispengya.sharding.tx.TxCommitStrategy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * LOCAL 提交部分失败场景（真实 MySQL，手工装配）：
 * 注入 ds_1 提交必败的包装池 → 重试 3 次后抛明细（已提交/未提交节点），
 * 随后回滚 → 已提交分片保留、失败分片撤销。
 * Spring 上下文无法为单测试注入策略/池，故手工装配路由链直连真库。
 */
public class LocalCommitStrategyIntegrationTest {

    private static final String MASTER_0_URL = "jdbc:mysql://localhost:33061/db_1?serverTimezone=UTC&useSSL=false&characterEncoding=utf8";
    private static final String MASTER_1_URL = "jdbc:mysql://localhost:33062/db_2?serverTimezone=UTC&useSSL=false&characterEncoding=utf8";
    private static final String TABLE = "t_local_commit";

    private ShardingDataSource shardingDataSource;
    private CommitFailingDataSource failingPool;

    @Before
    public void setUp() throws Exception {
        RouteContextHolder.clearDbHolder();
        RouteContextHolder.clearRoute();

        for (String url : new String[]{MASTER_0_URL, MASTER_1_URL}) {
            execute(url, "CREATE TABLE IF NOT EXISTS " + TABLE + " (id VARCHAR(64) PRIMARY KEY)");
            execute(url, "DELETE FROM " + TABLE);
        }

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

        Map<String, DataSource> pools = new HashMap<>();
        pools.put(RouteResolver.masterKey("ds_0"), DataSourceFactory.create("ds_0-master", ds0.getMaster()));
        failingPool = new CommitFailingDataSource(DataSourceFactory.create("ds_1-master", ds1.getMaster()));
        pools.put(RouteResolver.masterKey("ds_1"), failingPool);

        Map<Mode, TxCommitStrategy> strategies = new HashMap<>();
        strategies.put(Mode.LOCAL, new LocalCommitStrategy(3, 0));
        shardingDataSource = new ShardingDataSource(resolver, pools, strategies);
    }

    @After
    public void tearDown() throws Exception {
        RouteContextHolder.clearDbHolder();
        RouteContextHolder.clearRoute();
        execute(MASTER_0_URL, "DELETE FROM " + TABLE);
        execute(MASTER_1_URL, "DELETE FROM " + TABLE);
    }

    @Test
    public void partialCommitFailureRetriesThenDetailAndRollbackKeepsCommittedShard() throws Exception {
        ShardingConnection connection = (ShardingConnection) shardingDataSource.getConnection();
        connection.setAutoCommit(false);
        try {
            RouteContextHolder.setDbHolder("ds_0");
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("INSERT INTO " + TABLE + " (id) VALUES ('lc-ok')");
            }
            RouteContextHolder.setDbHolder("ds_1");
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("INSERT INTO " + TABLE + " (id) VALUES ('lc-fail')");
            }

            try {
                connection.commit();
                fail("ds_1 提交必败，应当抛出明细异常");
            } catch (ShardingException expected) {
                // 明细：已提交 ds_0、未提交 ds_1
                assertTrue(expected.getMessage().contains("ds_0#master"));
                assertTrue(expected.getMessage().contains("ds_1#master"));
                assertTrue(expected.getMessage().contains("重试 3 次"));
            }
            // 失败节点按配置重试 3 次
            assertEquals(3, failingPool.commitAttempts.get());

            // 事务层随后回滚未提交部分：ds_1 撤销、ds_0 已提交保留（LOCAL 无原子性，明细供对账）
            connection.rollback();
            assertEquals(1, count(MASTER_0_URL, "lc-ok"));
            assertEquals(0, count(MASTER_1_URL, "lc-fail"));
            assertEquals(0, count(MASTER_0_URL, "lc-fail"));
            assertEquals(0, count(MASTER_1_URL, "lc-ok"));
        } finally {
            connection.close();
        }
    }

    // ---------- helpers ----------

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
             java.sql.Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private int count(String url, String id) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, "root", "root");
             java.sql.Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + TABLE + " WHERE id = '" + id + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * 提交必败的连接池包装：getConnection 返回的连接 commit() 计数并抛 SQLException，其余委托
     */
    private static class CommitFailingDataSource implements DataSource {
        private final DataSource delegate;
        private final AtomicInteger commitAttempts = new AtomicInteger();

        private CommitFailingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection target = delegate.getConnection();
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if ("commit".equals(method.getName())) {
                            commitAttempts.incrementAndGet();
                            throw new SQLException("injected commit failure");
                        }
                        try {
                            return method.invoke(target, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface != null && iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("不支持 unwrap 到 " + iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface != null && iface.isInstance(this);
        }
    }
}
