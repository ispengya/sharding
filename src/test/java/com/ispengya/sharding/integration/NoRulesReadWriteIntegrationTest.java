package com.ispengya.sharding.integration;

import com.ispengya.sharding.annotation.Route;
import com.ispengya.sharding.config.ShardingProperties;
import com.ispengya.sharding.datasource.DataSourceFactory;
import com.ispengya.sharding.datasource.RouteResolver;
import com.ispengya.sharding.datasource.ShardingDataSource;
import com.ispengya.sharding.loadbalance.LoadBalances;
import com.ispengya.sharding.loadbalance.LoadBalancer;
import com.ispengya.sharding.loadbalance.RoundRobinLoadBalancer;
import com.ispengya.sharding.route.RouteContextHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

/**
 * 场景①：只配 slaves 不配 rules 的读写分流（真实 MySQL，手工装配路由链，不经 Spring 上下文）。
 * 共享的 application.yml 带 rules 无法为单个测试上下文清空，故本用例手工装配
 * properties → 连接池 → RouteResolver → ShardingDataSource（阶段 4 逻辑连接），
 * 直连验证 master 写 / slave 读。
 */
public class NoRulesReadWriteIntegrationTest {

    private static final String MASTER_URL = "jdbc:mysql://localhost:33061/db_1?serverTimezone=UTC&useSSL=false&characterEncoding=utf8";
    private static final String SLAVE_URL = "jdbc:mysql://localhost:33063/db_1?serverTimezone=UTC&useSSL=false&characterEncoding=utf8";
    private static final int SERVER_ID_MASTER = 1;
    private static final int SERVER_ID_SLAVE = 3;

    private ShardingDataSource shardingDataSource;

    @Before
    public void setUp() throws Exception {
        RouteContextHolder.clearDbHolder();
        RouteContextHolder.clearRoute();

        ShardingProperties properties = new ShardingProperties();
        properties.setPrimary("ds_0");

        ShardingProperties.DataSourceGroup group = new ShardingProperties.DataSourceGroup();
        group.setMaster(node(MASTER_URL));
        group.getSlaves().add(node(SLAVE_URL));
        group.setLoadBalance("round_robin");
        properties.getDataSources().put("ds_0", group);
        // 刻意不配置 rules

        Map<String, LoadBalancer> loadBalances = LoadBalances.collect(
                Arrays.asList((LoadBalancer) new RoundRobinLoadBalancer()));
        RouteResolver resolver = new RouteResolver("ds_0", properties.getDataSources(), loadBalances);

        Map<String, DataSource> pools = new HashMap<>();
        pools.put(RouteResolver.masterKey("ds_0"), DataSourceFactory.create("ds_0-master", group.getMaster()));
        pools.put(RouteResolver.slaveKey("ds_0", 0), DataSourceFactory.create("ds_0-slave-0", group.getSlaves().get(0)));
        shardingDataSource = new ShardingDataSource(resolver, pools);

        execute(MASTER_URL, "CREATE TABLE IF NOT EXISTS t_rw_plain (id VARCHAR(64) PRIMARY KEY)");
        execute(MASTER_URL, "DELETE FROM t_rw_plain");
    }

    @After
    public void tearDown() throws Exception {
        RouteContextHolder.clearDbHolder();
        RouteContextHolder.clearRoute();
        execute(MASTER_URL, "DELETE FROM t_rw_plain");
    }

    @Test
    public void writeFallsToMasterAndSlaveReadSeesReplicatedRow() throws Exception {
        // 未设置角色（默认 MASTER）→ 写落主库
        try (Connection connection = shardingDataSource.getConnection()) {
            assertEquals(SERVER_ID_MASTER, serverId(connection));
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("INSERT INTO t_rw_plain (id) VALUES ('rw-1')");
            }
        }

        // 轮询从库直到复制完成
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && countOnSlave() < 1) {
            Thread.sleep(200);
        }
        assertEquals(1, countOnSlave());

        // 显式 SLAVE → 读从库：行可见且节点为 slave（每个逻辑连接独立分发）
        RouteContextHolder.setRoute(Route.SLAVE);
        try (Connection connection = shardingDataSource.getConnection()) {
            assertEquals(SERVER_ID_SLAVE, serverId(connection));
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t_rw_plain WHERE id = 'rw-1'")) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    public void masterRoleReadsMasterEvenWhenSlavesConfigured() throws Exception {
        RouteContextHolder.setRoute(Route.MASTER);
        try (Connection connection = shardingDataSource.getConnection()) {
            assertEquals(SERVER_ID_MASTER, serverId(connection));
        }

        // 显式 MASTER 与未设置（null）语义一致
        RouteContextHolder.clearRoute();
        try (Connection connection = shardingDataSource.getConnection()) {
            assertEquals(SERVER_ID_MASTER, serverId(connection));
        }
    }

    @Test
    public void eachGetConnectionReturnsNewLogicalConnection() throws Exception {
        Connection first = shardingDataSource.getConnection();
        Connection second = shardingDataSource.getConnection();
        try {
            assertNotSame("连续 getConnection 应返回不同逻辑实例（REQUIRES_NEW 隔离）", first, second);
        } finally {
            first.close();
            second.close();
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

    private int serverId(Connection connection) throws Exception {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT @@server_id")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void execute(String url, String sql) throws Exception {
        try (Connection conn = java.sql.DriverManager.getConnection(url, "root", "root");
             Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private int countOnSlave() throws Exception {
        try (Connection conn = java.sql.DriverManager.getConnection(SLAVE_URL, "root", "root");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t_rw_plain")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
