package com.ispengya.sharding.datasource;

import com.ispengya.sharding.annotation.Route;
import com.ispengya.sharding.config.ShardingProperties;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.loadbalance.LoadBalances;
import com.ispengya.sharding.loadbalance.LoadBalancer;
import com.ispengya.sharding.loadbalance.RoundRobinLoadBalancer;
import com.ispengya.sharding.route.RouteContextHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 逻辑连接单测：懒加载时机、事务设置回填、enlist 与写标记、
 * 单分片快速路径与多分片循环提交、close 幂等、savepoint 跨分片拒绝。
 */
public class ShardingConnectionTest {

    private ShardingDataSource dataSource;
    private DataSource master0Pool;
    private DataSource master1Pool;
    private DataSource slave0Pool;
    private Connection master0;
    private Connection master1;
    private Connection slave0;
    private PreparedStatement statement;

    @Before
    public void setUp() throws Exception {
        RouteContextHolder.clearDbHolder();
        RouteContextHolder.clearRoute();

        ShardingProperties properties = new ShardingProperties();
        properties.setPrimary("ds_0");
        ShardingProperties.DataSourceGroup ds0 = new ShardingProperties.DataSourceGroup();
        ds0.setMaster(new ShardingProperties.NodeConfig());
        ds0.getSlaves().add(new ShardingProperties.NodeConfig());
        ds0.setLoadBalance("round_robin");
        ShardingProperties.DataSourceGroup ds1 = new ShardingProperties.DataSourceGroup();
        ds1.setMaster(new ShardingProperties.NodeConfig());
        properties.getDataSources().put("ds_0", ds0);
        properties.getDataSources().put("ds_1", ds1);

        Map<String, LoadBalancer> loadBalances = LoadBalances.collect(
                Arrays.asList((LoadBalancer) new RoundRobinLoadBalancer()));
        RouteResolver resolver = new RouteResolver("ds_0", properties.getDataSources(), loadBalances);

        master0 = mock(Connection.class);
        master1 = mock(Connection.class);
        slave0 = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        when(master0.prepareStatement(anyString())).thenReturn(statement);
        when(master1.prepareStatement(anyString())).thenReturn(statement);
        when(slave0.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        // 探测用默认值：池默认 autoCommit=true、隔离级别 RR(4)
        when(master0.getAutoCommit()).thenReturn(true);
        when(master0.getTransactionIsolation()).thenReturn(4);

        master0Pool = mock(DataSource.class);
        master1Pool = mock(DataSource.class);
        slave0Pool = mock(DataSource.class);
        when(master0Pool.getConnection()).thenReturn(master0);
        when(master1Pool.getConnection()).thenReturn(master1);
        when(slave0Pool.getConnection()).thenReturn(slave0);

        Map<String, DataSource> pools = new HashMap<>();
        pools.put(RouteResolver.masterKey("ds_0"), master0Pool);
        pools.put(RouteResolver.slaveKey("ds_0", 0), slave0Pool);
        pools.put(RouteResolver.masterKey("ds_1"), master1Pool);
        dataSource = new ShardingDataSource(resolver, pools);
    }

    @After
    public void tearDown() {
        RouteContextHolder.clearDbHolder();
        RouteContextHolder.clearRoute();
    }

    // ---------- 懒加载与回填 ----------

    @Test
    public void physicalConnectionFetchedOnlyAtFirstStatement() throws Exception {
        ShardingConnection connection = (ShardingConnection) dataSource.getConnection();
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(4);
        connection.setReadOnly(true);
        // 尚未创建语句：任何池都不该被访问
        verify(master0Pool, never()).getConnection();

        RouteContextHolder.setDbHolder("ds_0");
        connection.prepareStatement("SELECT 1");

        verify(master0Pool, times(1)).getConnection();
        // 回填：新取的物理连接补上事务设置
        verify(master0).setAutoCommit(false);
        verify(master0).setTransactionIsolation(4);
        verify(master0).setReadOnly(true);

        // 同一节点复用，不再取第二个连接
        connection.prepareStatement("SELECT 2");
        verify(master0Pool, times(1)).getConnection();
        connection.close();
    }

    @Test
    public void setterAfterFetchAppliesToExistingPhysical() throws Exception {
        ShardingConnection connection = (ShardingConnection) dataSource.getConnection();
        RouteContextHolder.setDbHolder("ds_0");
        connection.prepareStatement("SELECT 1");

        connection.setAutoCommit(false);
        verify(master0).setAutoCommit(false);
        connection.close();
    }

    // ---------- enlist 与写标记 ----------

    @Test
    public void enlistTracksTouchedAndWrittenNodes() throws Exception {
        ShardingConnection connection = (ShardingConnection) dataSource.getConnection();

        RouteContextHolder.setDbHolder("ds_0");
        PreparedStatement onDs0 = connection.prepareStatement("INSERT INTO t VALUES (1)");
        onDs0.executeUpdate();

        RouteContextHolder.setDbHolder("ds_1");
        PreparedStatement onDs1 = connection.prepareStatement("INSERT INTO t VALUES (2)");
        onDs1.executeUpdate();

        assertTrue(connection.getTouchedNodeKeys().contains("ds_0#master"));
        assertTrue(connection.getTouchedNodeKeys().contains("ds_1#master"));
        assertTrue(connection.getWrittenNodeKeys().contains("ds_0#master"));
        assertTrue(connection.getWrittenNodeKeys().contains("ds_1#master"));
        connection.close();
    }

    @Test
    public void readOnlyStatementDoesNotMarkWritten() throws Exception {
        ShardingConnection connection = (ShardingConnection) dataSource.getConnection();
        RouteContextHolder.setDbHolder("ds_0");
        PreparedStatement ps = connection.prepareStatement("SELECT 1");
        when(ps.executeQuery()).thenReturn(mock(java.sql.ResultSet.class));
        ps.executeQuery();

        assertEquals(1, connection.getTouchedNodeKeys().size());
        assertTrue(connection.getWrittenNodeKeys().isEmpty());
        connection.close();
    }

    @Test
    public void writeMarkUsesStatementCreationKeyEvenIfContextChanges() throws Exception {
        ShardingConnection connection = (ShardingConnection) dataSource.getConnection();
        RouteContextHolder.setDbHolder("ds_0");
        PreparedStatement ps = connection.prepareStatement("INSERT INTO t VALUES (1)");
        // 语句创建后上下文才变化（如轮询推进），写标记仍归属创建时的节点
        RouteContextHolder.setDbHolder("ds_1");
        ps.executeUpdate();

        assertTrue(connection.getWrittenNodeKeys().contains("ds_0#master"));
        assertFalse(connection.getWrittenNodeKeys().contains("ds_1#master"));
        connection.close();
    }

    // ---------- 提交 / 回滚 ----------

    @Test
    public void singleShardCommitUsesNativeFastPath() throws Exception {
        ShardingConnection connection = (ShardingConnection) dataSource.getConnection();
        RouteContextHolder.setDbHolder("ds_0");
        connection.prepareStatement("INSERT INTO t VALUES (1)");
        connection.commit();
        verify(master0, times(1)).commit();
        connection.close();
    }

    @Test
    public void multiShardCommitAndRollbackLoopAllNodes() throws Exception {
        ShardingConnection connection = (ShardingConnection) dataSource.getConnection();
        RouteContextHolder.setDbHolder("ds_0");
        connection.prepareStatement("INSERT INTO t VALUES (1)");
        RouteContextHolder.setDbHolder("ds_1");
        connection.prepareStatement("INSERT INTO t VALUES (2)");

        connection.commit();
        verify(master0, times(1)).commit();
        verify(master1, times(1)).commit();

        connection.rollback();
        verify(master0, times(1)).rollback();
        verify(master1, times(1)).rollback();
        connection.close();
    }

    @Test
    public void emptyTransactionCommitRollbackIsNoopWithoutPoolAccess() throws Exception {
        ShardingConnection connection = (ShardingConnection) dataSource.getConnection();
        connection.commit();
        connection.rollback();
        verify(master0Pool, never()).getConnection();
        connection.close();
    }

    @Test
    public void multiShardCommitAttemptsAllThenThrowsFirstFailure() throws Exception {
        ShardingConnection connection = (ShardingConnection) dataSource.getConnection();
        RouteContextHolder.setDbHolder("ds_0");
        connection.prepareStatement("INSERT INTO t VALUES (1)");
        RouteContextHolder.setDbHolder("ds_1");
        connection.prepareStatement("INSERT INTO t VALUES (2)");
        org.mockito.Mockito.doThrow(new SQLException("commit failed")).when(master1).commit();

        try {
            connection.commit();
            fail("应抛出首个提交失败异常");
        } catch (SQLException expected) {
            assertEquals("commit failed", expected.getMessage());
        }
        // 已尝试全部节点（master0 已提交）
        verify(master0, times(1)).commit();
        connection.close();
    }

    // ---------- close ----------

    @Test
    public void closeIsIdempotentAndReturnsAllPhysicalConnections() throws Exception {
        ShardingConnection connection = (ShardingConnection) dataSource.getConnection();
        RouteContextHolder.setDbHolder("ds_0");
        connection.prepareStatement("INSERT INTO t VALUES (1)");
        RouteContextHolder.setDbHolder("ds_1");
        connection.prepareStatement("INSERT INTO t VALUES (2)");

        connection.close();
        connection.close();
        verify(master0, times(1)).close();
        verify(master1, times(1)).close();

        try {
            connection.getAutoCommit();
            fail("close 后应拒绝操作");
        } catch (SQLException expected) {
            // 期望
        }
    }

    @Test
    public void eachGetConnectionReturnsNewLogicalInstance() {
        Connection first = dataSource.getConnection();
        Connection second = dataSource.getConnection();
        assertNotSame(first, second);
        assertTrue(first instanceof ShardingConnection);
    }

    // ---------- 事务设置 getter（doBegin 语义） ----------

    @Test
    public void transactionSettingsAnswerableBeforeAnyFetch() throws Exception {
        ShardingConnection connection = (ShardingConnection) dataSource.getConnection();
        // 未设置过 → 回答池默认（探测 primary 主库，不被路由上下文干扰）
        assertTrue(connection.getAutoCommit());
        assertEquals(4, connection.getTransactionIsolation());
        verify(master0Pool, times(1)).getConnection();

        connection.setAutoCommit(false);
        assertFalse(connection.getAutoCommit());
        connection.close();
    }

    // ---------- savepoint ----------

    @Test
    public void savepointRejectedWhenMultiplePhysicalsEnlisted() throws Exception {
        ShardingConnection connection = (ShardingConnection) dataSource.getConnection();
        RouteContextHolder.setDbHolder("ds_0");
        connection.prepareStatement("INSERT INTO t VALUES (1)");
        RouteContextHolder.setDbHolder("ds_1");
        connection.prepareStatement("INSERT INTO t VALUES (2)");

        try {
            connection.setSavepoint();
            fail("跨分片 savepoint 应拒绝");
        } catch (ShardingException expected) {
            // 期望
        }
        connection.close();
    }

    @Test
    public void savepointWorksOnSingleEnlistedPhysical() throws Exception {
        java.sql.Savepoint savepoint = mock(java.sql.Savepoint.class);
        when(savepoint.getSavepointName()).thenReturn("s1");
        when(master0.setSavepoint("s1")).thenReturn(savepoint);

        ShardingConnection connection = (ShardingConnection) dataSource.getConnection();
        RouteContextHolder.setDbHolder("ds_0");
        connection.prepareStatement("INSERT INTO t VALUES (1)");
        assertEquals("s1", connection.setSavepoint("s1").getSavepointName());
        verify(master0, times(1)).setSavepoint("s1");
        connection.close();
    }

    // ---------- 角色路由 ----------

    @Test
    public void slaveRoleFetchesFromSlavePool() throws Exception {
        ShardingConnection connection = (ShardingConnection) dataSource.getConnection();
        RouteContextHolder.setRoute(Route.SLAVE);
        connection.prepareStatement("SELECT 1");

        verify(slave0Pool, times(1)).getConnection();
        verify(master0Pool, never()).getConnection();
        connection.close();
    }
}
