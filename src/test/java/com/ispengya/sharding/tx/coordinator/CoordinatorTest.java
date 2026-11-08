package com.ispengya.sharding.tx.coordinator;

import com.ispengya.sharding.datasource.ShardingConnection;
import com.ispengya.sharding.datasource.XaBranch;
import com.ispengya.sharding.exception.ShardingException;
import org.junit.Before;
import org.junit.Test;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2PC 协调者状态机单测：prepare 全过/失败裁决、commit 中途失败停在 PREPARED、
 * rollback 按分支状态收尾、空事务无日志。
 */
public class CoordinatorTest {

    private static final String GLOBAL_ID = "gid-test-0001";

    private TransactionLog log;
    private Coordinator coordinator;
    private XAResource resource1;
    private XAResource resource2;
    private ShardingConnection connection;
    private Map<String, XaBranch> branches;

    @Before
    public void setUp() throws Exception {
        log = mock(TransactionLog.class);
        coordinator = new Coordinator(log);
        resource1 = mock(XAResource.class);
        resource2 = mock(XAResource.class);
        branches = new LinkedHashMap<>();
        branches.put("ds_0#master", new XaBranch("ds_0#master", resource1,
                XidSupport.create(GLOBAL_ID, "ds_0#master"), probeConnection()));
        branches.put("ds_1#master", new XaBranch("ds_1#master", resource2,
                XidSupport.create(GLOBAL_ID, "ds_1#master"), probeConnection()));
        connection = mock(ShardingConnection.class);
        when(connection.getXaBranches()).thenReturn(branches);
        when(connection.getXaGlobalId()).thenReturn(GLOBAL_ID);
    }

    /**
     * canCommit 探针所需的连接桩：executeQuery 恒有结果
     */
    private java.sql.Connection probeConnection() throws Exception {
        java.sql.Connection connection = mock(java.sql.Connection.class);
        java.sql.Statement statement = mock(java.sql.Statement.class);
        java.sql.ResultSet resultSet = mock(java.sql.ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        return connection;
    }

    @Test
    public void emptyTransactionIsNoopWithoutLog() throws Exception {
        when(connection.getXaBranches()).thenReturn(new LinkedHashMap<>());
        coordinator.prepare(connection);
        coordinator.canCommit(connection);
        coordinator.preCommit(connection);
        coordinator.commit(connection);
        coordinator.rollback(connection);
        verify(log, never()).record(anyString(), anyString(), any(), any());
        verify(log, never()).updateState(anyString(), anyString(), any());
    }

    // ---------- 3PC 阶段 ----------

    @Test
    public void canCommitRecordsBeginAndProbesAllBranches() throws Exception {
        coordinator.canCommit(connection);

        verify(log).record(eq(GLOBAL_ID), eq(TransactionLog.STATE_BEGIN),
                eq(Arrays.asList("ds_0#master", "ds_1#master")), any());
        // 两分支各探测一次（仅读，不做 END/PREPARE）
        for (XaBranch branch : branches.values()) {
            verify(branch.getConnection()).createStatement();
            assertEquals(XaBranch.State.STARTED, branch.getState());
        }
        verify(resource1, never()).prepare(any());
        verify(log, never()).updateState(anyString(), anyString(), any());
    }

    @Test
    public void canCommitProbeFailureThrowsWithNodeDetail() throws Exception {
        java.sql.Statement failing = mock(java.sql.Statement.class);
        java.sql.ResultSet broken = mock(java.sql.ResultSet.class);
        when(broken.next()).thenThrow(new java.sql.SQLException("probe failed"));
        when(failing.executeQuery(anyString())).thenReturn(broken);
        when(branches.get("ds_1#master").getConnection().createStatement()).thenReturn(failing);

        try {
            coordinator.canCommit(connection);
            fail("探针失败应当抛出异常");
        } catch (ShardingException expected) {
            assertTrue(expected.getMessage().contains("canCommit"));
            assertTrue(expected.getMessage().contains("ds_1#master"));
        }
        // canCommit 阶段不写终态（由策略层决定回滚）
        verify(log, never()).updateState(anyString(), anyString(), any());
    }

    @Test
    public void preCommitEndsPreparesAndWritesPreparedWithoutRecordingBegin() throws Exception {
        coordinator.preCommit(connection);

        // BEGIN 由 canCommit 负责，preCommit 不重复登记
        verify(log, never()).record(anyString(), anyString(), any(), any());
        verify(log).updateState(GLOBAL_ID, TransactionLog.STATE_PREPARED, null);
        verify(resource1).prepare(any());
        verify(resource2).prepare(any());
    }

    @Test
    public void prepareAllSuccessWritesBeginThenPrepared() throws Exception {
        coordinator.prepare(connection);

        verify(log).record(eq(GLOBAL_ID), eq(TransactionLog.STATE_BEGIN),
                eq(Arrays.asList("ds_0#master", "ds_1#master")), any());
        verify(log).updateState(GLOBAL_ID, TransactionLog.STATE_PREPARED, null);
        for (XaBranch branch : branches.values()) {
            verify(branch.getResource()).end(branch.getXid(), XAResource.TMSUCCESS);
            verify(branch.getResource()).prepare(branch.getXid());
            assertEquals(XaBranch.State.PREPARED, branch.getState());
        }
    }

    @Test
    public void prepareFailureRollsBackAllAndWritesRolledBack() throws Exception {
        doThrow(new XAException("prepare boom")).when(resource2).prepare(any());

        try {
            coordinator.prepare(connection);
            fail("prepare 失败应当抛出异常");
        } catch (ShardingException expected) {
            assertTrue(expected.getMessage().contains("ds_1#master"));
            assertNotNull(expected.getCause());
        }

        // 失败分支回滚；先成功的分支也回滚（全败全滚）
        verify(resource2).rollback(any());
        verify(resource1).rollback(any());
        verify(log).updateState(eq(GLOBAL_ID), eq(TransactionLog.STATE_ROLLED_BACK), anyString());
        verify(log, never()).updateState(eq(GLOBAL_ID), eq(TransactionLog.STATE_PREPARED), any());
    }

    @Test
    public void commitAllSuccessWritesCommitted() throws Exception {
        coordinator.prepare(connection);
        coordinator.commit(connection);

        verify(resource1).commit(any(), eq(false));
        verify(resource2).commit(any(), eq(false));
        verify(log).updateState(GLOBAL_ID, TransactionLog.STATE_COMMITTED, null);
        assertEquals(XaBranch.State.DONE, branches.get("ds_0#master").getState());
    }

    @Test
    public void commitMidwayFailureKeepsPreparedForRecovery() throws Exception {
        coordinator.prepare(connection);
        doThrow(new XAException("commit boom")).when(resource2).commit(any(), anyBoolean());

        try {
            coordinator.commit(connection);
            fail("commit 失败应当抛出异常");
        } catch (ShardingException expected) {
            assertTrue(expected.getMessage().contains("等待启动恢复"));
        }

        // 关键语义：日志保持 PREPARED（不写 COMMITTED），交由恢复兑现
        verify(log).updateState(eq(GLOBAL_ID), eq(TransactionLog.STATE_PREPARED), anyString());
        verify(log, never()).updateState(eq(GLOBAL_ID), eq(TransactionLog.STATE_COMMITTED), any());
        verify(resource1).commit(any(), eq(false));
        verify(resource2, times(1)).commit(any(), anyBoolean());
    }

    @Test
    public void rollbackFromStartedEndsWithFailThenRollsBack() throws Exception {
        coordinator.rollback(connection);

        for (XaBranch branch : branches.values()) {
            verify(branch.getResource()).end(branch.getXid(), XAResource.TMFAIL);
            verify(branch.getResource()).rollback(branch.getXid());
            assertEquals(XaBranch.State.DONE, branch.getState());
        }
        verify(log).updateState(GLOBAL_ID, TransactionLog.STATE_ROLLED_BACK, null);
    }

    @Test
    public void rollbackFromPreparedSkipsEnd() throws Exception {
        coordinator.prepare(connection);
        coordinator.rollback(connection);

        // 已 PREPARED 的分支直接 rollback，不再 end
        verify(resource1, never()).end(any(), eq(XAResource.TMFAIL));
        verify(resource1).rollback(any());
        verify(log).updateState(GLOBAL_ID, TransactionLog.STATE_ROLLED_BACK, null);
    }

    @Test
    public void xidRebuildsDeterministicallyFromLogValues() {
        // 恢复时按日志中的 globalId + 节点 key 重建 Xid 必须与原始分支相等
        assertEquals(XidSupport.create(GLOBAL_ID, "ds_0#master"),
                XidSupport.create(GLOBAL_ID, "ds_0#master"));
        assertTrue(XidSupport.matches(XidSupport.create(GLOBAL_ID, "ds_0#master"),
                GLOBAL_ID, "ds_0#master"));
        assertTrue(!XidSupport.matches(XidSupport.create(GLOBAL_ID, "other"),
                GLOBAL_ID, "ds_0#master"));
    }
}
