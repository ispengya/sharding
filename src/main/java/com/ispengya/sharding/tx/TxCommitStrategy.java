package com.ispengya.sharding.tx;

import com.ispengya.sharding.annotation.Mode;
import com.ispengya.sharding.datasource.ShardingConnection;

import java.sql.SQLException;

/**
 * 跨数据源提交协调策略 SPI：由 ShardingConnection.commit()/rollback() 按当前
 * @TxMode 模式调用（模式缺省 LOCAL）。注册套路与 algorithm/ 统一：
 * 实现类注册为 Spring Bean，按 getMode() 收敛，模式不可重复。
 * 单分片快速路径与多分片循环由各策略自行决定。
 */
public interface TxCommitStrategy {

    /**
     * 本策略对应的协调模式
     */
    Mode getMode();

    /**
     * 提交前准备阶段：LOCAL 无两阶段、空实现；XA 在此执行 prepare
     */
    default void prepare(ShardingConnection connection) throws SQLException {
        // LOCAL 模式无 prepare 阶段
    }

    /**
     * 提交本连接已 enlist 的全部物理连接
     */
    void commit(ShardingConnection connection) throws SQLException;

    /**
     * 回滚本连接已 enlist 的全部物理连接
     */
    void rollback(ShardingConnection connection) throws SQLException;
}
