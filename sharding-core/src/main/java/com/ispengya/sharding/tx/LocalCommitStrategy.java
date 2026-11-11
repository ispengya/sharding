package com.ispengya.sharding.tx;

import com.ispengya.sharding.annotation.Mode;
import com.ispengya.sharding.datasource.ShardingConnection;
import com.ispengya.sharding.exception.ShardingException;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 本地提交策略（Mode.LOCAL）：单分片走原生快速路径（与单库事务完全一致、不重试）；
 * 多分片循环提交/回滚，每节点失败重试 N 次；提交仍有失败时记录
 * 已提交/未提交明细日志并抛异常（无原子性，明细供对账修复，事务层随后回滚未提交部分）。
 */
@Slf4j
public class LocalCommitStrategy implements TxCommitStrategy {

    public static final int DEFAULT_RETRY_TIMES = 3;
    public static final long DEFAULT_RETRY_DELAY_MILLIS = 50;

    private final int retryTimes;
    private final long retryDelayMillis;

    public LocalCommitStrategy() {
        this(DEFAULT_RETRY_TIMES, DEFAULT_RETRY_DELAY_MILLIS);
    }

    public LocalCommitStrategy(int retryTimes, long retryDelayMillis) {
        this.retryTimes = retryTimes;
        this.retryDelayMillis = retryDelayMillis;
    }

    @Override
    public Mode getMode() {
        return Mode.LOCAL;
    }

    @Override
    public void commit(ShardingConnection connection) throws SQLException {
        Map<String, Connection> physicals = connection.getPhysicalConnections();
        if (physicals.isEmpty()) {
            return;
        }
        if (physicals.size() == 1) {
            // 单分片快速路径：原生提交，行为与单库事务一致
            physicals.values().iterator().next().commit();
            return;
        }

        Set<String> committed = new LinkedHashSet<>();
        Set<String> failed = new LinkedHashSet<>();
        SQLException first = null;
        for (Map.Entry<String, Connection> entry : physicals.entrySet()) {
            boolean ok = false;
            for (int attempt = 1; attempt <= retryTimes && !ok; attempt++) {
                try {
                    entry.getValue().commit();
                    committed.add(entry.getKey());
                    ok = true;
                } catch (SQLException e) {
                    if (first == null) {
                        first = e;
                    }
                    if (attempt < retryTimes) {
                        sleepBeforeRetry();
                    }
                }
            }
            if (!ok) {
                failed.add(entry.getKey());
            }
        }
        if (!failed.isEmpty()) {
            String detail = "跨分片提交部分失败：已提交=" + committed + "，未提交=" + failed
                    + "（每节点重试 " + retryTimes + " 次）";
            log.error(detail, first);
            throw new ShardingException(detail, first);
        }
    }

    @Override
    public void rollback(ShardingConnection connection) throws SQLException {
        Map<String, Connection> physicals = connection.getPhysicalConnections();
        if (physicals.isEmpty()) {
            return;
        }
        if (physicals.size() == 1) {
            physicals.values().iterator().next().rollback();
            return;
        }

        Set<String> failed = new LinkedHashSet<>();
        SQLException first = null;
        for (Map.Entry<String, Connection> entry : physicals.entrySet()) {
            boolean ok = false;
            for (int attempt = 1; attempt <= retryTimes && !ok; attempt++) {
                try {
                    entry.getValue().rollback();
                    ok = true;
                } catch (SQLException e) {
                    if (first == null) {
                        first = e;
                    }
                    if (attempt < retryTimes) {
                        sleepBeforeRetry();
                    }
                }
            }
            if (!ok) {
                failed.add(entry.getKey());
            }
        }
        if (!failed.isEmpty()) {
            String detail = "跨分片回滚失败：失败节点=" + failed + "（每节点重试 " + retryTimes + " 次）";
            log.error(detail, first);
            throw new ShardingException(detail, first);
        }
    }

    private void sleepBeforeRetry() {
        if (retryDelayMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(retryDelayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
