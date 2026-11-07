package com.ispengya.sharding.datasource;

import com.alibaba.druid.pool.DruidDataSource;
import com.ispengya.sharding.config.DbConfigValidator;
import com.ispengya.sharding.config.ShardingProperties;

import javax.sql.DataSource;

/**
 * 物理节点连接池构建：NodeConfig 配置对象 → Druid 连接池。
 * 主库与每个从库节点各自独立建池，参数互不影响。
 */
public final class DataSourceFactory {

    private DataSourceFactory() {
    }

    public static DataSource create(String nodeName, ShardingProperties.NodeConfig nodeConfig) {
        DbConfigValidator.validate(nodeName, nodeConfig);

        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setDriverClassName(nodeConfig.getDriverClassName());
        dataSource.setUrl(nodeConfig.getUrl());
        dataSource.setUsername(nodeConfig.getUsername());
        dataSource.setPassword(nodeConfig.getPassword());

        ShardingProperties.ConnectionConfig connection = nodeConfig.getConnection();

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
