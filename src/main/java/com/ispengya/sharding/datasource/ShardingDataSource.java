package com.ispengya.sharding.datasource;

import com.ispengya.sharding.annotation.Mode;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.tx.TxCommitStrategy;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 分片数据源：持有全部物理节点池（key = RouteResolver 物理节点 key）与
 * 提交协调策略注册表（Mode → TxCommitStrategy，缺省 LOCAL）。
 * 每次 getConnection() 返回新的 ShardingConnection（逻辑连接，保证 REQUIRES_NEW 不串台）。
 * 物理连接按每条 SQL 当时的路由上下文在 ShardingConnection 内懒加载分发。
 * 启动时探一次 primary 主库池的 autoCommit/隔离级别默认值，供逻辑连接
 * 在未取物理连接前回答事务管理器的询问（对齐 LazyConnectionDataSourceProxy 语义）。
 */
@Slf4j
public class ShardingDataSource implements DataSource {

    private final RouteResolver routeResolver;
    private final Map<String, DataSource> pools;
    private final Map<Mode, TxCommitStrategy> commitStrategies;

    private volatile Boolean defaultAutoCommit;
    private volatile Integer defaultTransactionIsolation;

    public ShardingDataSource(RouteResolver routeResolver, Map<String, DataSource> pools,
                              Map<Mode, TxCommitStrategy> commitStrategies) {
        this.routeResolver = routeResolver;
        this.pools = Collections.unmodifiableMap(new LinkedHashMap<>(pools));
        this.commitStrategies = Collections.unmodifiableMap(new LinkedHashMap<>(commitStrategies));
    }

    /**
     * 按协调模式取提交策略；模式缺省（未标 @TxMode 的单分片/只读事务）按 LOCAL
     */
    public TxCommitStrategy getCommitStrategy(Mode mode) {
        TxCommitStrategy strategy = commitStrategies.get(mode != null ? mode : Mode.LOCAL);
        if (strategy == null) {
            throw new ShardingException("Mode." + mode + " 的提交策略未注册（该模式尚未实现）");
        }
        return strategy;
    }

    @Override
    public Connection getConnection() {
        return new ShardingConnection(this);
    }

    @Override
    public Connection getConnection(String username, String password) {
        // 物理节点凭据由各自池配置决定，忽略调用方传入的凭据
        return getConnection();
    }

    /**
     * 按当前路由上下文（分库结果 + 读写角色）解析物理节点 key
     */
    public String resolveNodeKey() {
        return routeResolver.resolve(
                com.ispengya.sharding.route.RouteContextHolder.getDbHolder(),
                com.ispengya.sharding.route.RouteContextHolder.getRoute());
    }

    /**
     * 从物理节点池取连接（懒加载入口，由 ShardingConnection 调用）
     */
    public Connection fetchPhysical(String nodeKey) throws SQLException {
        DataSource pool = pools.get(nodeKey);
        if (pool == null) {
            throw new ShardingException("路由到未注册的物理节点: " + nodeKey
                    + "（已注册: " + pools.keySet() + "）");
        }
        return pool.getConnection();
    }

    /**
     * 从物理节点池取 XA 连接（Mode.XA 分支开启用；池须为 XADataSource）
     */
    public XAConnection fetchXaConnection(String nodeKey) throws SQLException {
        DataSource pool = pools.get(nodeKey);
        if (pool == null) {
            throw new ShardingException("路由到未注册的物理节点: " + nodeKey
                    + "（已注册: " + pools.keySet() + "）");
        }
        if (!(pool instanceof XADataSource)) {
            throw new ShardingException("节点 " + nodeKey + " 的连接池不支持 XA（需要 XADataSource）");
        }
        return ((XADataSource) pool).getXAConnection();
    }

    /**
     * 按物理节点 key 取池（恢复裁决用）
     */
    public DataSource getPool(String nodeKey) {
        return pools.get(nodeKey);
    }

    /**
     * 探测 primary 主库池的默认 autoCommit/隔离级别（懒探测，失败返回 null 由调用方兜底）
     */
    Boolean probeDefaultAutoCommit() {
        ensureProbed();
        return defaultAutoCommit;
    }

    Integer probeDefaultTransactionIsolation() {
        ensureProbed();
        return defaultTransactionIsolation;
    }

    private void ensureProbed() {
        if (defaultAutoCommit != null && defaultTransactionIsolation != null) {
            return;
        }
        synchronized (this) {
            if (defaultAutoCommit != null && defaultTransactionIsolation != null) {
                return;
            }
            // resolve(null, null) 即 primary 主库物理 key（primary#master）
            try (Connection connection = fetchPhysical(routeResolver.resolve(null, null))) {
                defaultAutoCommit = connection.getAutoCommit();
                defaultTransactionIsolation = connection.getTransactionIsolation();
            } catch (Exception e) {
                log.debug("探测主库默认连接属性失败（将在首次需要时取连接兜底）: {}", e.getMessage());
            }
        }
    }

    public RouteResolver getRouteResolver() {
        return routeResolver;
    }

    // ---------- DataSource 模板方法（分片场景不适用的保持最小实现） ----------

    @Override
    public java.io.PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(java.io.PrintWriter out) {
        // 不支持
    }

    @Override
    public void setLoginTimeout(int seconds) {
        // 不支持：各池超时由 connection.maxWait 配置
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("getParentLogger");
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
}
