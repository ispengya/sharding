package com.ispengya.sharding.tx;

import com.ispengya.sharding.annotation.Mode;
import com.ispengya.sharding.datasource.ShardingConnection;
import com.ispengya.sharding.tx.coordinator.Coordinator;

import java.sql.SQLException;

/**
 * 两阶段提交策略（Mode.XA）：prepare/commit/rollback 全部委托 Coordinator 编排。
 * 与 LOCAL 不同，无单分片"跳过日志"的快速路径——XA 分支一旦开启必须走完整
 * end/prepare/commit 生命周期，统一落日志保证恢复语义一致。
 */
public class TwoPhaseCommitStrategy implements TxCommitStrategy {

    private final Coordinator coordinator;

    public TwoPhaseCommitStrategy(Coordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    public Mode getMode() {
        return Mode.XA;
    }

    @Override
    public void prepare(ShardingConnection connection) throws SQLException {
        coordinator.prepare(connection);
    }

    @Override
    public void commit(ShardingConnection connection) throws SQLException {
        coordinator.commit(connection);
    }

    @Override
    public void rollback(ShardingConnection connection) throws SQLException {
        coordinator.rollback(connection);
    }
}
