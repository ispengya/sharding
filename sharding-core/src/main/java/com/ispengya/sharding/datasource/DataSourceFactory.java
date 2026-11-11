package com.ispengya.sharding.datasource;

import com.alibaba.druid.pool.xa.DruidXADataSource;
import com.ispengya.sharding.config.DbConfigValidator;
import com.ispengya.sharding.config.ShardingConfig;

import javax.sql.DataSource;

/**
 * 物理节点连接池构建：NodeConfig 配置对象 → Druid XA 连接池。
 * 统一使用 DruidXADataSource（继承 DruidDataSource，池参数完全一致）：
 * Mode.LOCAL 走普通 getConnection，Mode.XA 走 getXAConnection 开分支——
 * 同一池服务两种模式，避免"用了 XA 才发现池不支持"的运行期错误。
 * 主库与每个从库节点各自独立建池，参数互不影响。
 */
public final class DataSourceFactory {

    private DataSourceFactory() {
    }

    public static DataSource create(String nodeName, ShardingConfig.NodeConfig nodeConfig) {
        DbConfigValidator.validate(nodeName, nodeConfig);

        DruidXADataSource dataSource = new DruidXADataSource();
        dataSource.setDriverClassName(nodeConfig.getDriverClassName());
        dataSource.setUrl(nodeConfig.getUrl());
        dataSource.setUsername(nodeConfig.getUsername());
        dataSource.setPassword(nodeConfig.getPassword());

        ShardingConfig.ConnectionConfig connection = nodeConfig.getConnection();

        dataSource.setInitialSize(connection.getInitialSize());
        dataSource.setMinIdle(connection.getMinIdle());
        dataSource.setMaxActive(connection.getMaxActive());
        dataSource.setMaxWait(connection.getMaxWait());
        dataSource.setValidationQuery(connection.getValidationQuery());
        dataSource.setTestWhileIdle(connection.isTestWhileIdle());
        dataSource.setTestOnBorrow(connection.isTestOnBorrow());
        dataSource.setTestOnReturn(connection.isTestOnReturn());
        dataSource.setTimeBetweenEvictionRunsMillis(connection.getTimeBetweenEvictionRunsMillis());
        dataSource.setMinEvictableIdleTimeMillis(connection.getMinEvictableIdleTimeMillis());
        return dataSource;
    }
}
