package com.ispengya.sharding.tx;

import com.ispengya.sharding.annotation.Mode;
import com.ispengya.sharding.datasource.ShardingConnection;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.tx.coordinator.Coordinator;
import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 三阶段提交策略（Mode.THREE_PC）：canCommit → preCommit → commit，各阶段带超时。
 * 超时规则：
 * - canCommit/preCommit 阶段超时或失败 → 全部回滚（abort），日志 ROLLED_BACK——
 *   承诺点（PREPARED）尚未做出，abort 安全
 * - commit 阶段超时 → 日志已在 PREPARED，按日志状态决策：保持承诺不回滚，
 *   抛出交由启动恢复兑现（presumed commit）
 * 协调者侧的"超时决策"即启动恢复：PREPARED → 补 commit，BEGIN → 补 rollback。
 * 阶段调用跑在守护线程池上以便限时；超时后 cancel(true) 中断（真实 JDBC 挂起
 * 可能滞留至套接字超时，由恢复兜底——见 journal 已知限制）。
 */
@Slf4j
public class ThreePhaseCommitStrategy implements TxCommitStrategy {

    public static final long DEFAULT_STAGE_TIMEOUT_MILLIS = 10_000;

    private final Coordinator coordinator;
    private final long stageTimeoutMillis;
    private final ExecutorService stageExecutor;

    public ThreePhaseCommitStrategy(Coordinator coordinator) {
        this(coordinator, DEFAULT_STAGE_TIMEOUT_MILLIS);
    }

    public ThreePhaseCommitStrategy(Coordinator coordinator, long stageTimeoutMillis) {
        this.coordinator = coordinator;
        this.stageTimeoutMillis = stageTimeoutMillis;
        this.stageExecutor = Executors.newCachedThreadPool(new ThreadFactory() {
            private final AtomicInteger sequence = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "sharding-3pc-stage-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    @Override
    public Mode getMode() {
        return Mode.THREE_PC;
    }

    /**
     * SPI 钩子（ShardingConnection.commit 内先于 commit 调用）：
     * canCommit 阶段 → preCommit 阶段；任一失败/超时 → 全部回滚并抛出
     */
    @Override
    public void prepare(ShardingConnection connection) throws SQLException {
        String stage = "canCommit";
        try {
            runStage(stage, () -> coordinator.canCommit(connection));
            stage = "preCommit";
            runStage(stage, () -> coordinator.preCommit(connection));
        } catch (Exception e) {
            try {
                coordinator.rollback(connection);
            } catch (Exception rollbackFailure) {
                e.addSuppressed(rollbackFailure);
                log.error("3PC 中止回滚失败（待启动恢复重试）", rollbackFailure);
            }
            throw new ShardingException("3PC " + stage + " 阶段失败，已全部回滚：globalId="
                    + connection.getXaGlobalId(), e);
        }
    }

    @Override
    public void commit(ShardingConnection connection) throws SQLException {
        try {
            runStage("commit", () -> coordinator.commit(connection));
        } catch (StageTimeoutException timeout) {
            // 按日志状态决策：preCommit 已写 PREPARED（承诺成立）→ 不回滚，交恢复兑现
            throw new ShardingException("3PC commit 阶段超时，日志保持 PREPARED 待启动恢复兑现：globalId="
                    + connection.getXaGlobalId(), timeout);
        }
        // 非超时失败：coordinator.commit 已保持 PREPARED 并抛出，原样传递
    }

    @Override
    public void rollback(ShardingConnection connection) throws SQLException {
        coordinator.rollback(connection);
    }

    private void runStage(String stage, StageAction action) throws SQLException {
        Future<Void> future = stageExecutor.submit((Callable<Void>) () -> {
            action.run();
            return null;
        });
        try {
            future.get(stageTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new StageTimeoutException(stage, e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new SQLException("3PC " + stage + " 阶段被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof SQLException) {
                throw (SQLException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new SQLException("3PC " + stage + " 阶段失败", cause);
        }
    }

    @FunctionalInterface
    private interface StageAction {
        void run() throws SQLException;
    }
}
