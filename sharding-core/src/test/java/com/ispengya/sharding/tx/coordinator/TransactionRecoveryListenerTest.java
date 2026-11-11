package com.ispengya.sharding.tx.coordinator;

import com.ispengya.sharding.datasource.ShardingDataSource;
import org.junit.Before;
import org.junit.Test;

import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 启动恢复决策单测（mock 日志注入各状态）：
 * 表不存在跳过、PREPARED→presumed commit、BEGIN→presumed abort、
 * 分支已终态幂等跳过、参与者缺失保留非终态待重试、扫描顺序。
 */
public class TransactionRecoveryListenerTest {

    private static final String GLOBAL_ID = "gid-recover-0001";
    private static final String NODE = "ds_0#master";

    private TransactionLog log;
    private ShardingDataSource dataSource;
    private XAResource resource;
    private TransactionRecoveryListener listener;

    @Before
    public void setUp() throws Exception {
        log = mock(TransactionLog.class);
        dataSource = mock(ShardingDataSource.class);
        resource = mock(XAResource.class);
        listener = new TransactionRecoveryListener(log, dataSource);

        XAConnection xaConnection = mock(XAConnection.class);
        when(xaConnection.getXAResource()).thenReturn(resource);
        // 池需同时是 DataSource 与 XADataSource（与 DruidXADataSource 一致）
        DataSource xaPool = mock(DataSource.class, withSettings().extraInterfaces(XADataSource.class));
        when(((XADataSource) xaPool).getXAConnection()).thenReturn(xaConnection);
        when(dataSource.getPool(NODE)).thenReturn(xaPool);
    }

    @Test
    public void missingLogTableSkipsRecoveryQuietly() {
        when(log.existsQuietly()).thenReturn(false);
        listener.recoverPending();
        verify(log, never()).findByState(anyString());
    }

    @Test
    public void preparedEntryWithInDoubtBranchIsCommitted() throws Exception {
        when(log.existsQuietly()).thenReturn(true);
        when(log.findByState(TransactionLog.STATE_PREPARED)).thenReturn(Collections.singletonList(
                new TransactionLog.LogEntry(GLOBAL_ID, NODE)));
        when(resource.recover(anyInt())).thenReturn(new Xid[]{
                XidSupport.create(GLOBAL_ID, NODE)});

        listener.recoverPending();

        verify(resource).commit(any(Xid.class), eq(false));
        verify(log).updateState(eq(GLOBAL_ID), eq(TransactionLog.STATE_COMMITTED), anyString());
    }

    @Test
    public void beginEntryWithInDoubtBranchIsRolledBack() throws Exception {
        when(log.existsQuietly()).thenReturn(true);
        when(log.findByState(TransactionLog.STATE_PREPARED)).thenReturn(Collections.emptyList());
        when(log.findByState(TransactionLog.STATE_BEGIN)).thenReturn(Collections.singletonList(
                new TransactionLog.LogEntry(GLOBAL_ID, NODE)));
        when(resource.recover(anyInt())).thenReturn(new Xid[]{
                XidSupport.create(GLOBAL_ID, NODE)});

        listener.recoverPending();

        verify(resource).rollback(any(Xid.class));
        verify(log).updateState(eq(GLOBAL_ID), eq(TransactionLog.STATE_ROLLED_BACK), anyString());
    }

    @Test
    public void alreadyResolvedBranchIsIdempotent() throws Exception {
        when(log.existsQuietly()).thenReturn(true);
        when(log.findByState(TransactionLog.STATE_PREPARED)).thenReturn(Collections.singletonList(
                new TransactionLog.LogEntry(GLOBAL_ID, NODE)));
        // 名册中已无该分支（此前已提交/回滚）→ 不再裁决，直接推进终态
        when(resource.recover(anyInt())).thenReturn(new Xid[0]);

        listener.recoverPending();

        verify(resource, never()).commit(any(Xid.class), anyBoolean());
        verify(resource, never()).rollback(any(Xid.class));
        verify(log).updateState(eq(GLOBAL_ID), eq(TransactionLog.STATE_COMMITTED), anyString());
    }

    @Test
    public void missingParticipantKeepsEntryPendingForRetry() throws Exception {
        when(log.existsQuietly()).thenReturn(true);
        when(log.findByState(TransactionLog.STATE_PREPARED)).thenReturn(Collections.singletonList(
                new TransactionLog.LogEntry(GLOBAL_ID, NODE + ",ds_gone#master")));
        when(dataSource.getPool(NODE)).thenReturn(null);
        when(resource.recover(anyInt())).thenReturn(new Xid[]{
                XidSupport.create(GLOBAL_ID, NODE)});

        listener.recoverPending();

        // 有参与者无法裁决 → 保留 PREPARED，下次启动重试
        verify(log).updateState(eq(GLOBAL_ID), eq(TransactionLog.STATE_PREPARED), anyString());
        verify(log, never()).updateState(eq(GLOBAL_ID), eq(TransactionLog.STATE_COMMITTED), any());
    }

    @Test
    public void scanOrderChecksPreparedBeforeBegin() throws Exception {
        when(log.existsQuietly()).thenReturn(true);
        when(log.findByState(anyString())).thenReturn(Collections.emptyList());

        listener.recoverPending();

        verify(log).findByState(TransactionLog.STATE_PREPARED);
        verify(log).findByState(TransactionLog.STATE_BEGIN);
        verify(log, never()).updateState(anyString(), anyString(), any());
    }
}
