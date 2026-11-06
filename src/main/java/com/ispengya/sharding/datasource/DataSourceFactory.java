package com.ispengya.sharding.datasource;

import com.alibaba.druid.pool.DruidDataSource;
import com.ispengya.sharding.config.DbConfigValidator;
import com.ispengya.sharding.config.ShardingProperties;

import javax.sql.DataSource;

/**
 * 物理数据源构建：配置对象 → Druid 连接池。
 * 从 ShardingAutoConfiguration 拆出，装配逻辑之外只关心这一件事。
 */
public final class DataSourceFactory {

    private DataSourceFactory() {
    }

    public static DataSource create(String dsName, ShardingProperties.DataSourceConfig dataSourceConfig) {
        DbConfigValidator.validate(dsName, dataSourceConfig);

        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setDriverClassName(dataSourceConfig.getDriverClassName());
        dataSource.setUrl(dataSourceConfig.getUrl());
        dataSource.setUsername(dataSourceConfig.getUsername());
        dataSource.setPassword(dataSourceConfig.getPassword());

        ShardingProperties.ConnectionConfig connection = dataSourceConfig.getConnection();

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
