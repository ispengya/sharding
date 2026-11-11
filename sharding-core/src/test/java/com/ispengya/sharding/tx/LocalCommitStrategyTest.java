package com.ispengya.sharding.tx;

import com.ispengya.sharding.annotation.Mode;
import com.ispengya.sharding.datasource.ShardingConnection;
import com.ispengya.sharding.exception.ShardingException;
import org.junit.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * LOCAL 提交策略单测：模式、空/单/多连接分支、失败重试次数、明细内容、回滚循环。
 */
public class LocalCommitStrategyTest {

    private final LocalCommitStrategy strategy = new LocalCommitStrategy(3, 0);

    private ShardingConnection connectionWith(Connection... connections) throws Exception {
        // 通过反射构造一个仅含物理连接的逻辑连接（不走池），用于策略直测
        ShardingConnection connection = mock(ShardingConnection.class);
        Map<String, Connection> physicals = new LinkedHashMap<>();
        for (int i = 0; i < connections.length; i++) {
            physicals.put("ds_" + i + "#master", connections[i]);
        }
        org.mockito.Mockito.when(connection.getPhysicalConnections()).thenReturn(physicals);
        return connection;
    }

    @Test
    public void modeIsLocal() {
        assertEquals(Mode.LOCAL, strategy.getMode());
    }

    @Test
    public void prepareIsNoopForLocal() throws Exception {
        strategy.prepare(connectionWith(mock(Connection.class)));
        // LOCAL 无两阶段：不抛即正确
    }

    @Test
    public void emptyConnectionIsNoop() throws Exception {
        Connection physical = mock(Connection.class);
        strategy.commit(connectionWith());
        strategy.rollback(connectionWith());
        verify(physical, times(0)).commit();
    }

    @Test
    public void singleConnectionUsesNativeCommitWithoutRetry() throws Exception {
        Connection physical = mock(Connection.class);
        strategy.commit(connectionWith(physical));
        verify(physical, times(1)).commit();

        // 单连接失败不重试（原生快速路径，行为与单库事务一致）
        Connection failing = mock(Connection.class);
        doThrow(new SQLException("single failed")).when(failing).commit();
        try {
            strategy.commit(connectionWith(failing));
            fail("应当抛出原始 SQLException");
        } catch (SQLException expected) {
            assertEquals("single failed", expected.getMessage());
        }
        verify(failing, times(1)).commit();
    }

    @Test
    public void multiConnectionCommitsAll() throws Exception {
        Connection first = mock(Connection.class);
        Connection second = mock(Connection.class);
        strategy.commit(connectionWith(first, second));
        verify(first, times(1)).commit();
        verify(second, times(1)).commit();
    }

    @Test
    public void failedNodeRetriedConfiguredTimesThenDetailThrown() throws Exception {
        Connection ok = mock(Connection.class);
        Connection failing = mock(Connection.class);
        doThrow(new SQLException("boom")).when(failing).commit();

        try {
            strategy.commit(connectionWith(ok, failing));
            fail("应当抛出部分失败异常");
        } catch (ShardingException expected) {
            assertTrue(expected.getMessage().contains("ds_0#master"));
            assertTrue(expected.getMessage().contains("ds_1#master"));
            assertTrue(expected.getMessage().contains("重试 3 次"));
            assertNotNull(expected.getCause());
        }
        verify(failing, times(3)).commit();
        verify(ok, times(1)).commit();
    }

    @Test
    public void transientFailureRecoversWithinRetryBudget() throws Exception {
        Connection flaky = mock(Connection.class);
        // 前两次失败、第三次成功
        doThrow(new SQLException("flaky-1")).doThrow(new SQLException("flaky-2")).doNothing()
                .when(flaky).commit();

        strategy.commit(connectionWith(mock(Connection.class), flaky));
        verify(flaky, times(3)).commit();
    }

    @Test
    public void multiRollbackLoopsWithRetryAndDetail() throws Exception {
        Connection ok = mock(Connection.class);
        Connection failing = mock(Connection.class);
        doThrow(new SQLException("rollback-boom")).when(failing).rollback();

        strategy.rollback(connectionWith(ok));
        verify(ok, times(1)).rollback();

        try {
            strategy.rollback(connectionWith(ok, failing));
            fail("应当抛出回滚失败异常");
        } catch (ShardingException expected) {
            assertTrue(expected.getMessage().contains("ds_1#master"));
        }
        verify(failing, times(3)).rollback();
    }

    @Test
    public void singleRollbackUsesNativePath() throws Exception {
        Connection physical = mock(Connection.class);
        strategy.rollback(connectionWith(physical));
        verify(physical, times(1)).rollback();
    }
}
