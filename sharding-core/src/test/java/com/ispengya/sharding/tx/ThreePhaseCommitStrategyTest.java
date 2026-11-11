package com.ispengya.sharding.tx;

import com.ispengya.sharding.annotation.Mode;
import com.ispengya.sharding.datasource.ShardingConnection;
import com.ispengya.sharding.datasource.XaBranch;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.tx.coordinator.Coordinator;
import com.ispengya.sharding.tx.coordinator.TransactionLog;
import com.ispengya.sharding.tx.coordinator.XidSupport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 3PC 策略单测：三阶段成功链路、canCommit 拒绝 → abort、
 * preCommit 阶段超时 → abort、commit 阶段超时 → 按日志状态保持 PREPARED 不回滚。
 */
public class ThreePhaseCommitStrategyTest {

    private static final String GLOBAL_ID = "gid-3pc-0001";
    private static final long SHORT_TIMEOUT_MILLIS = 300;

    private TransactionLog log;
    private Coordinator coordinator;
    private ThreePhaseCommitStrategy strategy;
    private XAResource resource1;
    private XAResource resource2;
    private ShardingConnection connection;
    private Map<String, XaBranch> branches;
    private Statement probeStatement0;

    @Before
    public void setUp() throws Exception {
        log = mock(TransactionLog.class);
        coordinator = new Coordinator(log);
        strategy = new ThreePhaseCommitStrategy(coordinator, SHORT_TIMEOUT_MILLIS);
        resource1 = mock(XAResource.class);
        resource2 = mock(XAResource.class);
        branches = new LinkedHashMap<>();
        Connection probe0 = probeConnection();
        Connection probe1 = probeConnection();
        branches.put("ds_0#master", new XaBranch("ds_0#master", resource1,
                XidSupport.create(GLOBAL_ID, "ds_0#master"), probe0));
        branches.put("ds_1#master", new XaBranch("ds_1#master", resource2,
                XidSupport.create(GLOBAL_ID, "ds_1#master"), probe1));
        // 提前取得探针 Statement（避免在 doThrow().when() 求值链中调用已打桩方法）
        probeStatement0 = probe0.createStatement();
        connection = mock(ShardingConnection.class);
        when(connection.getXaBranches()).thenReturn(branches);
        when(connection.getXaGlobalId()).thenReturn(GLOBAL_ID);
    }

    @After
    public void tearDown() {
        // 策略持有的守护线程池无需关闭（daemon），仅确保中断标记不泄漏
        Thread.interrupted();
    }

    private Connection probeConnection() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        return connection;
    }

    @Test
    public void modeIsThreePhase() {
        assertEquals(Mode.THREE_PC, strategy.getMode());
    }

    @Test
    public void fullThreePhaseFlowEndsCommitted() throws Exception {
        strategy.prepare(connection);
        strategy.commit(connection);

        // canCommit：仅探测；preCommit：END+PREPARE；commit：XA COMMIT
        verify(resource1).end(any(), eq(XAResource.TMSUCCESS));
        verify(resource1).prepare(any());
        verify(resource1).commit(any(), eq(false));
        verify(resource2).commit(any(), eq(false));
        verify(log).updateState(GLOBAL_ID, TransactionLog.STATE_PREPARED, null);
        verify(log).updateState(GLOBAL_ID, TransactionLog.STATE_COMMITTED, null);
    }

    @Test
    public void canCommitRejectionRollsBackAllAndWritesRolledBack() throws Exception {
        // 探针失败（分支1）
        doThrow(new SQLException("injected probe failure"))
                .when(probeStatement0).executeQuery(anyString());

        try {
            strategy.prepare(connection);
            fail("canCommit 拒绝应当抛出异常");
        } catch (ShardingException expected) {
            assertTrue(expected.getMessage().contains("canCommit"));
            assertTrue(expected.getMessage().contains("已全部回滚"));
            // 节点明细在 cause 层（协调者抛出的 canCommit 拒绝）
            assertTrue(expected.getCause().getMessage().contains("ds_0#master"));
        }

        verify(resource1).rollback(any());
        verify(resource2).rollback(any());
        // rollback 路径 detail 为 null → any()（anyString 不匹配 null）
        verify(log).updateState(eq(GLOBAL_ID), eq(TransactionLog.STATE_ROLLED_BACK), any());
        verify(log, never()).updateState(eq(GLOBAL_ID), eq(TransactionLog.STATE_PREPARED), any());
    }

    @Test
    public void preCommitStageTimeoutAbortsAndWritesRolledBack() throws Exception {
        // XA PREPARE 挂起 → 阶段超时（cancel 中断后以方法声明的异常收场）
        doAnswer(invocation -> hangThenThrow(XAException::new)).when(resource1).prepare(any());

        try {
            strategy.prepare(connection);
            fail("preCommit 超时应当抛出异常");
        } catch (ShardingException expected) {
            assertTrue(expected.getMessage().contains("preCommit"));
            assertTrue(expected.getMessage().contains("已全部回滚"));
            assertTrue(rootCauseIsStageTimeout(expected));
        }

        // 超时 → abort：日志落 ROLLED_BACK；被中断的 stage 线程与主线程兜底回滚可能各写一次（语义幂等），
        // 故按状态语义断言（atLeastOnce），绝不出现 PREPARED 承诺才是关键
        verify(log, atLeastOnce()).updateState(eq(GLOBAL_ID), eq(TransactionLog.STATE_ROLLED_BACK), any());
        verify(log, never()).updateState(eq(GLOBAL_ID), eq(TransactionLog.STATE_PREPARED), any());
        verify(resource2).rollback(any());
    }

    @Test
    public void commitStageTimeoutKeepsPreparedForRecovery() throws Exception {
        // preCommit 成功（承诺已落盘）
        strategy.prepare(connection);
        verify(log).updateState(GLOBAL_ID, TransactionLog.STATE_PREPARED, null);

        // XA COMMIT 挂起 → 超时
        doAnswer(invocation -> hangThenThrow(XAException::new)).when(resource1).commit(any(), anyBoolean());

        try {
            strategy.commit(connection);
            fail("commit 超时应当抛出异常");
        } catch (ShardingException expected) {
            assertTrue(expected.getMessage().contains("保持 PREPARED"));
            assertTrue(rootCauseIsStageTimeout(expected));
        }

        // 按日志状态决策：承诺成立，不回滚、不改写终态（交启动恢复兑现）
        verify(log, never()).updateState(eq(GLOBAL_ID), eq(TransactionLog.STATE_ROLLED_BACK), any());
        verify(log, never()).updateState(eq(GLOBAL_ID), eq(TransactionLog.STATE_COMMITTED), any());
        verify(resource2, never()).rollback(any());
    }

    /**
     * 挂起直到被中断（模拟参与者阶段卡死），随后以声明异常收场
     */
    private Object hangThenThrow(java.util.function.Supplier<XAException> exceptionFactory)
            throws XAException {
        try {
            Thread.sleep(60_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw exceptionFactory.get();
        }
        return null;
    }

    @Test
    public void rollbackDelegatesToCoordinator() throws Exception {
        strategy.rollback(connection);
        verify(resource1).end(any(), eq(XAResource.TMFAIL));
        verify(resource1).rollback(any());
        verify(log).updateState(GLOBAL_ID, TransactionLog.STATE_ROLLED_BACK, null);
    }

    private boolean rootCauseIsStageTimeout(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof StageTimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
