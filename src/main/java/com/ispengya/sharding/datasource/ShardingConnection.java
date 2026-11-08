package com.ispengya.sharding.datasource;

import com.ispengya.sharding.annotation.Mode;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.tx.TxCommitStrategy;
import com.ispengya.sharding.tx.TxContextHolder;
import com.ispengya.sharding.tx.coordinator.XidSupport;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

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
 * 逻辑连接：事务管理器绑定的就是它，内部 1:N 分发到物理节点池。
 * <ul>
 *   <li>懒加载：第一条语句创建时才按当时路由上下文取物理连接并回填事务设置</li>
 *   <li>enlist：记录触达/写过的物理节点；写入第二个数据源时执行跨库校验
 *       （缺 @TxMode 报错、REQUIRES_NEW/NESTED 禁用）</li>
 *   <li>commit/rollback 按当前 @TxMode 模式委托 TxCommitStrategy
 *       （缺省 LOCAL：单分片原生快速路径、多分片循环+重试+明细）</li>
 *   <li>close 幂等，归还全部物理连接并清理跨库标记</li>
 * </ul>
 * autoCommit/隔离级别/readOnly 的 getter 在未取物理连接前返回逻辑值或池默认
 * （对齐 LazyConnectionDataSourceProxy，供 DataSourceTransactionManager.doBegin 使用）。
 */
public class ShardingConnection implements Connection {

    private final ShardingDataSource dataSource;
    private final Map<String, Connection> physicalConnections = new LinkedHashMap<>();
    private final Set<String> writtenNodeKeys = new LinkedHashSet<>();

    // Mode.XA 分支状态：首条语句取物理连接时 XA START（先于任何 SQL），提交/回滚时收尾
    private final Map<String, XaBranch> xaBranches = new LinkedHashMap<>();
    private final Map<String, javax.sql.XAConnection> xaConnections = new LinkedHashMap<>();
    private String xaGlobalId;

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
            if (isXaBranchNeeded()) {
                connection = startXaBranch(nodeKey);
            } else {
                connection = dataSource.fetchPhysical(nodeKey);
                backfill(connection);
            }
            physicalConnections.put(nodeKey, connection);
        }
        return connection;
    }

    /**
     * 需要开启 XA 分支：模式为 XA 且处于活动事务（未开事务时模式不生效，保持普通连接语义）
     */
    private boolean isXaBranchNeeded() {
        return TxContextHolder.getMode() == Mode.XA
                && TransactionSynchronizationManager.isActualTransactionActive();
    }

    /**
     * 取 XA 连接 → 回填事务设置 → XA START（必须先于该节点上的任何 SQL）
     */
    private Connection startXaBranch(String nodeKey) throws SQLException {
        javax.sql.XAConnection xaConnection = dataSource.fetchXaConnection(nodeKey);
        Connection connection = xaConnection.getConnection();
        try {
            backfill(connection);
            Xid xid = XidSupport.create(xaGlobalId(), nodeKey);
            XAResource resource = xaConnection.getXAResource();
            resource.start(xid, XAResource.TMNOFLAGS);
            xaConnections.put(nodeKey, xaConnection);
            xaBranches.put(nodeKey, new XaBranch(nodeKey, resource, xid, connection));
            return connection;
        } catch (XAException e) {
            try {
                xaConnection.close();
            } catch (SQLException ignore) {
                // 归还失败不掩盖启动失败
            }
            throw new SQLException("XA START 失败（节点 " + nodeKey + "）", e);
        }
    }

    private String xaGlobalId() {
        if (xaGlobalId == null) {
            xaGlobalId = XidSupport.newGlobalId();
        }
        return xaGlobalId;
    }

    /**
     * XA 分支表（nodeKey → 分支），供协调者编排；无分支 = 空事务
     */
    public Map<String, XaBranch> getXaBranches() {
        return xaBranches;
    }

    /**
     * 本逻辑连接的 XA 全局事务 id（开启过分支才非 null）
     */
    public String getXaGlobalId() {
        return xaGlobalId;
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

    /**
     * 已 enlist 的物理连接快照（key → 连接），供提交策略遍历；只读视图
     */
    public Map<String, Connection> getPhysicalConnections() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(physicalConnections));
    }

    // ---------- 提交 / 回滚 / 关闭 ----------

    @Override
    public void commit() throws SQLException {
        checkOpen();
        TxCommitStrategy strategy = dataSource.getCommitStrategy(TxContextHolder.getMode());
        strategy.prepare(this);
        strategy.commit(this);
    }

    @Override
    public void rollback() throws SQLException {
        checkOpen();
        TxCommitStrategy strategy = dataSource.getCommitStrategy(TxContextHolder.getMode());
        strategy.rollback(this);
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
        clearCrossDatasourceMarkIfAny();
        // XA 连接归还（与物理连接同底层、close 幂等）；未裁决分支由池复位逻辑处置，
        // prepared 分支在 MySQL 侧保持 in-doubt 等待恢复
        for (javax.sql.XAConnection xaConnection : xaConnections.values()) {
            try {
                xaConnection.close();
            } catch (SQLException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        xaConnections.clear();
        xaBranches.clear();
        xaGlobalId = null;
        physicalConnections.clear();
        writtenNodeKeys.clear();
        if (first != null) {
            throw first;
        }
    }

    /**
     * 写入 ≥2 数据源的连接关闭时清理跨库标记（标记的设置与清理对称，
     * 保证线程复用不残留；REQUIRES_NEW 内层连接写名单不足 2 不会误清外层标记）
     */
    private void clearCrossDatasourceMarkIfAny() {
        if (writtenNodeKeys.size() >= 2) {
            TxContextHolder.clearCrossDatasourceWritten();
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
     * 包装语句：executeUpdate/execute/executeBatch 时把该语句创建时所属的节点标记为"写过"，
     * 并在事务内执行跨库校验（写第二个数据源必须标 @TxMode、禁用 REQUIRES_NEW/NESTED）
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
                        validateBeforeWriteMark(nodeKey);
                        writtenNodeKeys.add(nodeKey);
                        if (TransactionSynchronizationManager.isActualTransactionActive()
                                && writtenNodeKeys.size() >= 2) {
                            TxContextHolder.markCrossDatasourceWritten();
                        }
                    }
                    return method.invoke(statement, args);
                });
    }

    /**
     * 写入校验（在计入写名单之前执行）：
     * 1. REQUIRES_NEW 挂起的外层已写入多数据源 → 禁止再开独立事务写
     * 2. 写入第二个数据源：必须已标 @TxMode；REQUIRES_NEW/NESTED 禁用
     * 非事务的逐条自动提交不校验；跨数据源只读不进写名单、天然豁免
     */
    private void validateBeforeWriteMark(String nodeKey) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        if (isRequiresNew() && TxContextHolder.isCrossDatasourceWritten()) {
            throw new ShardingException("跨数据源事务仅支持 REQUIRED：外层事务已写入多个数据源，禁止 REQUIRES_NEW（节点 "
                    + nodeKey + "）");
        }
        if (writtenNodeKeys.size() + 1 >= 2) {
            if (TxContextHolder.getMode() == null) {
                throw new ShardingException("事务写入了多个数据源但未标注 @TxMode：请在事务入口方法标注 @TxMode(Mode.LOCAL)"
                        + "（单数据源写与跨数据源只读无需标注；节点 " + nodeKey + "）");
            }
            if (isRequiresNew() || isNested()) {
                throw new ShardingException("跨数据源事务仅支持 REQUIRED，禁用 REQUIRES_NEW/NESTED（节点 " + nodeKey + "）");
            }
        }
    }

    /**
     * 当前是否处于 REQUIRES_NEW（存在被挂起的外层事务）；
     * 首次开启的 REQUIRES_NEW 无外层可挂起，与 REQUIRED 等价，不拦（见 journal）
     */
    private boolean isRequiresNew() {
        TransactionStatus status = currentStatus();
        return status instanceof DefaultTransactionStatus
                && ((DefaultTransactionStatus) status).getSuspendedResources() != null;
    }

    /**
     * 当前是否处于 NESTED（savepoint 语义）
     */
    private boolean isNested() {
        TransactionStatus status = currentStatus();
        return status != null && status.hasSavepoint();
    }

    private TransactionStatus currentStatus() {
        try {
            return TransactionAspectSupport.currentTransactionStatus();
        } catch (NoTransactionException e) {
            return null;
        }
    }

    // ---------- savepoint：创建时拒绝跨分片，回滚按归属连接（JDBC savepoint 本就是单连接语义） ----------

    /**
     * savepoint 归属的物理连接（创建时记录）：后续即便 enlist 增长，
     * 回滚/释放仍落在创建它的连接上——NESTED 被跨分片校验拒绝后，
     * 事务层回滚 savepoint 不会被"多连接"守卫反噬。
     */
    private Connection savepointOwner;

    /**
     * 取创建 savepoint 的连接；无记录时按"至多一个物理连接"守卫
     */
    private Connection savepointConnection(String operation) throws SQLException {
        checkOpen();
        if (savepointOwner != null) {
            return savepointOwner;
        }
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
        // 创建时仍拒绝已跨分片的连接（跨分片 NESTED 语义未定义）
        checkOpen();
        if (physicalConnections.size() > 1) {
            throw new ShardingException("setSavepoint：跨数据源事务不支持 savepoint（NESTED 场景请保持单分片写）");
        }
        Connection owner = physicalConnections.size() == 1
                ? physicalConnections.values().iterator().next() : currentPhysical();
        savepointOwner = owner;
        return owner.setSavepoint();
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        checkOpen();
        if (physicalConnections.size() > 1) {
            throw new ShardingException("setSavepoint：跨数据源事务不支持 savepoint（NESTED 场景请保持单分片写）");
        }
        Connection owner = physicalConnections.size() == 1
                ? physicalConnections.values().iterator().next() : currentPhysical();
        savepointOwner = owner;
        return owner.setSavepoint(name);
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        savepointConnection("rollback(savepoint)").rollback(savepoint);
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        savepointConnection("releaseSavepoint").releaseSavepoint(savepoint);
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
        clearCrossDatasourceMarkIfAny();
        for (javax.sql.XAConnection xaConnection : xaConnections.values()) {
            try {
                xaConnection.close();
            } catch (SQLException ignore) {
                // abort 路径尽力而为
            }
        }
        xaConnections.clear();
        xaBranches.clear();
        xaGlobalId = null;
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
