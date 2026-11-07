package com.ispengya.sharding.datasource;

import com.ispengya.sharding.exception.ShardingException;

import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLClientInfoException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 逻辑连接（阶段 4）：事务管理器绑定的就是它，内部 1:N 分发到物理节点池。
 * <ul>
 *   <li>懒加载：第一条语句创建时才按当时路由上下文取物理连接并回填事务设置</li>
 *   <li>enlist：记录本连接触达的物理节点与写过的节点（阶段 5 跨库校验的判据）</li>
 *   <li>commit/rollback 只操作已登记连接：单连接走原生快速路径，多连接循环提交（阶段 5 占位）</li>
 *   <li>close 幂等，归还全部物理连接</li>
 * </ul>
 * autoCommit/隔离级别/readOnly 的 getter 在未取物理连接前返回逻辑值或池默认
 * （对齐 LazyConnectionDataSourceProxy，供 DataSourceTransactionManager.doBegin 使用）。
 */
public class ShardingConnection implements Connection {

    private final ShardingDataSource dataSource;
    private final Map<String, Connection> physicalConnections = new LinkedHashMap<>();
    private final Set<String> writtenNodeKeys = new LinkedHashSet<>();

    private Boolean autoCommit;
    private Integer transactionIsolation;
    private boolean readOnly;
    private boolean closed;

    ShardingConnection(ShardingDataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ---------- 事务设置：逻辑记录 + 已取物理连接同步回填 ----------

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkOpen();
        this.autoCommit = autoCommit;
        for (Connection connection : physicalConnections.values()) {
            connection.setAutoCommit(autoCommit);
        }
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        checkOpen();
        if (autoCommit != null) {
            return autoCommit;
        }
        Boolean pooled = dataSource.probeDefaultAutoCommit();
        if (pooled != null) {
            return pooled;
        }
        return currentPhysical().getAutoCommit();
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        checkOpen();
        this.transactionIsolation = level;
        for (Connection connection : physicalConnections.values()) {
            connection.setTransactionIsolation(level);
        }
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        checkOpen();
        if (transactionIsolation != null) {
            return transactionIsolation;
        }
        Integer pooled = dataSource.probeDefaultTransactionIsolation();
        if (pooled != null) {
            return pooled;
        }
        return currentPhysical().getTransactionIsolation();
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        checkOpen();
        this.readOnly = readOnly;
        for (Connection connection : physicalConnections.values()) {
            connection.setReadOnly(readOnly);
        }
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    // ---------- 懒加载与 enlist ----------

    /**
     * 按当前路由上下文取（或复用）物理连接，并回填已记录的事务设置
     */
    private Connection currentPhysical() throws SQLException {
        return physicalFor(dataSource.resolveNodeKey());
    }

    private Connection physicalFor(String nodeKey) throws SQLException {
        checkOpen();
        Connection connection = physicalConnections.get(nodeKey);
        if (connection == null) {
            connection = dataSource.fetchPhysical(nodeKey);
            backfill(connection);
            physicalConnections.put(nodeKey, connection);
        }
        return connection;
    }

    private void backfill(Connection connection) throws SQLException {
        if (autoCommit != null) {
            connection.setAutoCommit(autoCommit);
        }
        if (transactionIsolation != null) {
            connection.setTransactionIsolation(transactionIsolation);
        }
        if (readOnly) {
            connection.setReadOnly(true);
        }
    }

    /**
     * 本连接触达的物理节点 key（enlist 名单）
     */
    public Set<String> getTouchedNodeKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(physicalConnections.keySet()));
    }

    /**
     * 执行过写语句（executeUpdate/executeBatch 等）的物理节点 key
     */
    public Set<String> getWrittenNodeKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(writtenNodeKeys));
    }

    // ---------- 提交 / 回滚 / 关闭 ----------

    @Override
    public void commit() throws SQLException {
        checkOpen();
        if (physicalConnections.isEmpty()) {
            return;
        }
        if (physicalConnections.size() == 1) {
            // 单分片快速路径：原生提交，行为与改造前完全一致
            physicalConnections.values().iterator().next().commit();
            return;
        }
        // 多分片循环提交（阶段 5 LocalCommitStrategy 接管：重试 + 明细日志）
        SQLException first = null;
        for (Map.Entry<String, Connection> entry : physicalConnections.entrySet()) {
            try {
                entry.getValue().commit();
            } catch (SQLException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    @Override
    public void rollback() throws SQLException {
        checkOpen();
        if (physicalConnections.isEmpty()) {
            return;
        }
        if (physicalConnections.size() == 1) {
            physicalConnections.values().iterator().next().rollback();
            return;
        }
        SQLException first = null;
        for (Map.Entry<String, Connection> entry : physicalConnections.entrySet()) {
            try {
                entry.getValue().rollback();
            } catch (SQLException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        SQLException first = null;
        for (Connection connection : physicalConnections.values()) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        physicalConnections.clear();
        writtenNodeKeys.clear();
        if (first != null) {
            throw first;
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    private void checkOpen() throws SQLException {
        if (closed) {
            throw new SQLException("Connection is closed");
        }
    }

    // ---------- 语句创建（写标记入口；节点 key 在创建时捕获，与语句归属一致） ----------

    @Override
    public Statement createStatement() throws SQLException {
        String nodeKey = dataSource.resolveNodeKey();
        return wrap(physicalFor(nodeKey).createStatement(), nodeKey);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        String nodeKey = dataSource.resolveNodeKey();
        return wrap(physicalFor(nodeKey).createStatement(resultSetType, resultSetConcurrency), nodeKey);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        String nodeKey = dataSource.resolveNodeKey();
        return wrap(physicalFor(nodeKey).createStatement(resultSetType, resultSetConcurrency, resultSetHoldability),
                nodeKey);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        String nodeKey = dataSource.resolveNodeKey();
        return wrap(physicalFor(nodeKey).prepareStatement(sql), nodeKey);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
            throws SQLException {
        String nodeKey = dataSource.resolveNodeKey();
        return wrap(physicalFor(nodeKey).prepareStatement(sql, resultSetType, resultSetConcurrency), nodeKey);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
                                              int resultSetHoldability) throws SQLException {
        String nodeKey = dataSource.resolveNodeKey();
        return wrap(physicalFor(nodeKey).prepareStatement(sql, resultSetType, resultSetConcurrency,
                resultSetHoldability), nodeKey);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        String nodeKey = dataSource.resolveNodeKey();
        return wrap(physicalFor(nodeKey).prepareStatement(sql, autoGeneratedKeys), nodeKey);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        String nodeKey = dataSource.resolveNodeKey();
        return wrap(physicalFor(nodeKey).prepareStatement(sql, columnIndexes), nodeKey);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        String nodeKey = dataSource.resolveNodeKey();
        return wrap(physicalFor(nodeKey).prepareStatement(sql, columnNames), nodeKey);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        String nodeKey = dataSource.resolveNodeKey();
        return wrap(physicalFor(nodeKey).prepareCall(sql), nodeKey);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
            throws SQLException {
        String nodeKey = dataSource.resolveNodeKey();
        return wrap(physicalFor(nodeKey).prepareCall(sql, resultSetType, resultSetConcurrency), nodeKey);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
                                         int resultSetHoldability) throws SQLException {
        String nodeKey = dataSource.resolveNodeKey();
        return wrap(physicalFor(nodeKey).prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability),
                nodeKey);
    }

    /**
     * 包装语句：executeUpdate/execute/executeBatch 时把该语句创建时所属的节点标记为"写过"
     */
    @SuppressWarnings("unchecked")
    private <T extends Statement> T wrap(T statement, String nodeKey) {
        Class<?> iface = statement instanceof CallableStatement ? CallableStatement.class
                : statement instanceof PreparedStatement ? PreparedStatement.class
                : Statement.class;
        return (T) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{iface},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("executeUpdate".equals(name) || "execute".equals(name)
                            || "executeBatch".equals(name) || "executeLargeUpdate".equals(name)) {
                        writtenNodeKeys.add(nodeKey);
                    }
                    return method.invoke(statement, args);
                });
    }

    // ---------- savepoint：仅单物理连接语义明确，跨分片直接拒绝 ----------

    /**
     * 取本连接唯一可参与 savepoint 的物理连接：已 enlist 多个 → 拒绝；
     * 恰好一个 → 直接用它（不再解析路由，避免轮询推进取到别的从库）；零个 → 按当前上下文取
     */
    private Connection singlePhysical(String operation) throws SQLException {
        checkOpen();
        if (physicalConnections.size() > 1) {
            throw new ShardingException(operation + "：跨数据源事务不支持 savepoint（NESTED 场景请保持单分片写）");
        }
        if (physicalConnections.size() == 1) {
            return physicalConnections.values().iterator().next();
        }
        return currentPhysical();
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        return singlePhysical("setSavepoint").setSavepoint();
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        return singlePhysical("setSavepoint").setSavepoint(name);
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        singlePhysical("rollback(savepoint)").rollback(savepoint);
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        singlePhysical("releaseSavepoint").releaseSavepoint(savepoint);
    }

    // ---------- 其余 Connection 方法：委托当前路由物理连接 ----------

    @Override
    public String nativeSQL(String sql) throws SQLException {
        return currentPhysical().nativeSQL(sql);
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        return currentPhysical().getMetaData();
    }

    @Override
    public String getCatalog() throws SQLException {
        return currentPhysical().getCatalog();
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        currentPhysical().setCatalog(catalog);
    }

    @Override
    public String getSchema() throws SQLException {
        return currentPhysical().getSchema();
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        currentPhysical().setSchema(schema);
    }

    @Override
    public int getHoldability() throws SQLException {
        return currentPhysical().getHoldability();
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        currentPhysical().setHoldability(holdability);
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return currentPhysical().getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        currentPhysical().clearWarnings();
    }

    @Override
    public Clob createClob() throws SQLException {
        return currentPhysical().createClob();
    }

    @Override
    public Blob createBlob() throws SQLException {
        return currentPhysical().createBlob();
    }

    @Override
    public NClob createNClob() throws SQLException {
        return currentPhysical().createNClob();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        return currentPhysical().createSQLXML();
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        return currentPhysical().createStruct(typeName, attributes);
    }

    @Override
    public java.sql.Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        return currentPhysical().createArrayOf(typeName, elements);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        return currentPhysical().getClientInfo();
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        return currentPhysical().getClientInfo(name);
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        try {
            currentPhysical().setClientInfo(name, value);
        } catch (SQLException e) {
            throw new SQLClientInfoException(e.getMessage(), e.getSQLState(), e.getErrorCode(), null);
        }
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        try {
            currentPhysical().setClientInfo(properties);
        } catch (SQLException e) {
            throw new SQLClientInfoException(e.getMessage(), e.getSQLState(), e.getErrorCode(), null);
        }
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        return currentPhysical().getTypeMap();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        currentPhysical().setTypeMap(map);
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        if (closed) {
            return false;
        }
        if (physicalConnections.isEmpty()) {
            return true;
        }
        for (Connection connection : physicalConnections.values()) {
            if (!connection.isValid(timeout)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        for (Connection connection : physicalConnections.values()) {
            connection.abort(executor);
        }
        physicalConnections.clear();
        writtenNodeKeys.clear();
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        currentPhysical().setNetworkTimeout(executor, milliseconds);
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        return currentPhysical().getNetworkTimeout();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface != null && iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("不支持 unwrap 到 " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface != null && iface.isInstance(this);
    }

    @Override
    public String toString() {
        return "ShardingConnection{touched=" + physicalConnections.keySet()
                + ", written=" + writtenNodeKeys + ", closed=" + closed + "}";
    }
}
