package com.ispengya.sharding.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * sharding.* 配置属性绑定：数据源连接信息与分库分表规则。
 */
@Data
@ConfigurationProperties(prefix = ShardingProperties.DYNAMIC_DATASOURCE_PREFIX)
public class ShardingProperties {

    public static final String DYNAMIC_DATASOURCE_PREFIX = "sharding";

    private String primary;
    private Map<String, TableShardingConfig> tableSharding;
    private DBShardingConfig dbSharding;
    private Map<String, DataSourceConfig> dataSources;


    @Data
    public static class DataSourceConfig {
        private String driverClassName;
        private String url;
        private String username;
        private String password;
        private ConnectionConfig connection = new ConnectionConfig();
    }

    @Data
    public static class TableShardingConfig {
        private int tableCount;
        private String logicTableName;
        private String shardingKey;
        private String strategyKey = "hash";
        private String strategyClassName;
    }

    @Data
    public static class DBShardingConfig {
        private int dbCount;
        private String shardingKey;
        private String strategyKey = "hash";
        private String strategyClassName;
    }

    @Data
    public static class ConnectionConfig {
        private int initialSize = 5;
        private int minIdle = 5;
        private int maxActive = 20;
        private int maxWait = 60000;
        private String validationQuery = "SELECT 1";
        private boolean testWhileIdle = true;
        private boolean testOnBorrow = true;
        private boolean testOnReturn = false;
        private int timeBetweenEvictionRunsMillis = 60000;
        private int minEvictableIdleTimeMillis = 300000;
    }

}


