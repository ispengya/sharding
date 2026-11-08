package com.ispengya.sharding.tx.coordinator;

import com.ispengya.sharding.exception.ShardingException;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * 2PC 事务日志（表建在 primary 数据源，由框架新建）：
 * 状态机 BEGIN → PREPARED → COMMITTED / ROLLED_BACK，每次写入独立连接、独立提交（durable）。
 * 校验策略：首次写日志时 CREATE TABLE IF NOT EXISTS；失败则探测表是否已存在
 * （有表无建表权限的场景放行），仍不存在 → 报错并附 DDL 供手动执行；
 * 恢复扫描遇表不存在（42S02）静默跳过——未使用 XA 的应用零负担。
 */
@Slf4j
public class TransactionLog {

    public static final String TABLE = "sharding_tx_log";

    public static final String STATE_BEGIN = "BEGIN";
    public static final String STATE_PREPARED = "PREPARED";
    public static final String STATE_COMMITTED = "COMMITTED";
    public static final String STATE_ROLLED_BACK = "ROLLED_BACK";

    public static final String DDL =
            "CREATE TABLE IF NOT EXISTS " + TABLE + " (\n"
                    + "  global_id VARCHAR(64) NOT NULL PRIMARY KEY,\n"
                    + "  state VARCHAR(16) NOT NULL,\n"
                    + "  participants VARCHAR(512),\n"
                    + "  detail VARCHAR(1024),\n"
                    + "  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP\n"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    /**
     * 历史表兼容：早期版本建表未指定字符集（继承库默认 latin1），detail 中文写入会被拒
     */
    private static final String ALTER_CHARSET =
            "ALTER TABLE " + TABLE + " CONVERT TO CHARACTER SET utf8mb4";

    private static final String SQLSTATE_TABLE_MISSING = "42S02";

    private final Supplier<DataSource> primaryPoolSupplier;
    private volatile boolean ensured;

    public TransactionLog(DataSource primaryPool) {
        this(() -> primaryPool);
    }

    /**
     * 池以 Supplier 惰性提供：打破"数据源装配收集提交策略 → 策略依赖日志 →
     * 日志依赖数据源"的启动期循环依赖（首次使用时才解析池）
     */
    public TransactionLog(Supplier<DataSource> primaryPoolSupplier) {
        this.primaryPoolSupplier = primaryPoolSupplier;
    }

    private DataSource pool() {
        DataSource pool = primaryPoolSupplier.get();
        if (pool == null) {
            throw new ShardingException("事务日志的 primary 连接池未就绪");
        }
        return pool;
    }

    /**
     * 首次使用（XA 模式）时校验/新建日志表（utf8mb4，兼容中文明细）——幂等，之后直接返回
     */
    public synchronized void ensureTable() {
        if (ensured) {
            return;
        }
        try (Connection connection = pool().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(DDL);
            try {
                statement.execute(ALTER_CHARSET);
            } catch (SQLException alterFailure) {
                // 表已是 utf8mb4 或无 ALTER 权限：新表不受影响，尽力而为
                log.debug("日志表字符集转换跳过: {}", alterFailure.getMessage());
            }
            ensured = true;
            return;
        } catch (SQLException createFailure) {
            // 常见于"表已由运维预建、账号无 CREATE 权限"：探测存在即放行
            if (tableExists()) {
                ensured = true;
                return;
            }
            throw new ShardingException("无法创建 2PC 事务日志表 " + TABLE
                    + "，使用 Mode.XA 前请手动执行 DDL：\n" + DDL, createFailure);
        }
    }

    /**
     * 日志表是否存在（静默版，供恢复扫描）：表不存在 → false；其他错误照抛
     */
    public boolean existsQuietly() {
        try (Connection connection = pool().getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT 1 FROM " + TABLE + " LIMIT 1")) {
            return true;
        } catch (SQLException e) {
            if (isTableMissing(e)) {
                return false;
            }
            throw new ShardingException("探测事务日志表 " + TABLE + " 失败", e);
        }
    }

    /**
     * 记录新事务（BEGIN）
     */
    public void record(String globalId, String state, List<String> participants, String detail) {
        ensureTable();
        String participantText = participants == null ? "" : String.join(",", participants);
        try (Connection connection = pool().getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO " + TABLE
                    + " (global_id, state, participants, detail) VALUES ('"
                    + escape(globalId) + "', '" + escape(state) + "', '"
                    + escape(participantText) + "', '" + escape(detail) + "')");
        } catch (SQLException e) {
            throw new ShardingException("写入事务日志失败（" + state + "）: " + globalId, e);
        }
    }

    /**
     * 推进状态（行不存在时静默跳过——未记录过的事务无需落终态）
     */
    public void updateState(String globalId, String state, String detail) {
        ensureTable();
        try (Connection connection = pool().getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE " + TABLE + " SET state = '" + escape(state)
                    + "', detail = '" + escape(detail == null ? "" : detail)
                    + "' WHERE global_id = '" + escape(globalId) + "'");
        } catch (SQLException e) {
            throw new ShardingException("更新事务日志失败（" + state + "）: " + globalId, e);
        }
    }

    /**
     * 按状态扫描（恢复用）
     */
    public List<LogEntry> findByState(String state) {
        ensureTable();
        List<LogEntry> entries = new ArrayList<>();
        try (Connection connection = pool().getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT global_id, participants FROM " + TABLE
                     + " WHERE state = '" + escape(state) + "'")) {
            while (rs.next()) {
                entries.add(new LogEntry(rs.getString(1), rs.getString(2)));
            }
        } catch (SQLException e) {
            throw new ShardingException("扫描事务日志失败（" + state + "）", e);
        }
        return entries;
    }

    private boolean tableExists() {
        try (Connection connection = pool().getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT 1 FROM " + TABLE + " LIMIT 1")) {
            return true;
        } catch (SQLException e) {
            if (isTableMissing(e)) {
                return false;
            }
            return false;
        }
    }

    private static boolean isTableMissing(SQLException e) {
        return SQLSTATE_TABLE_MISSING.equals(e.getSQLState()) || e.getErrorCode() == 1146;
    }

    /**
     * 日志值转义：框架生成的 globalId/状态均为受控字符集，参与者 key 来自配置；
     * 仍做单引号转义兜底
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }

    /**
     * 日志行：globalId + 参与者清单（物理节点 key，逗号分隔）
     */
    public static final class LogEntry {
        private final String globalId;
        private final String participants;

        public LogEntry(String globalId, String participants) {
            this.globalId = globalId;
            this.participants = participants;
        }

        public String getGlobalId() {
            return globalId;
        }

        public List<String> participantList() {
            if (participants == null || participants.isEmpty()) {
                return new ArrayList<>();
            }
            return Arrays.asList(participants.split(","));
        }
    }
}
