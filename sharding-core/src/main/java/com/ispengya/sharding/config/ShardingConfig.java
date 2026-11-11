package com.ispengya.sharding.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * sharding.* 配置模型（纯 POJO，零 Spring 依赖）：
 * dataSources = 逻辑数据源组（master 必配、slaves 选配即读写分离开关、loadBalance 组内策略）；
 * rules = 按逻辑表的分片规则（db 段定范围与分库键、table 段定分表，均选配 algorithm/algorithmParams）。
 * 不配置 rules 的逻辑表不分片，落 primary 直通。
 *
 * starter（boot2 / boot3）的 ShardingProperties 继承本类并叠加 @ConfigurationProperties 绑定。
 */
@Data
public class ShardingConfig {

    private String primary;
    private Map<String, DataSourceGroup> dataSources = new LinkedHashMap<>();
    private Map<String, ShardingRuleConfig> rules = new LinkedHashMap<>();

    /**
     * 逻辑数据源组：一主多从
     */
    @Data
    public static class DataSourceGroup {
        /**
         * 主库节点（必配）
         */
        private NodeConfig master;
        /**
         * 从库节点列表（选配；配置即开启读写分离，阶段 3 生效）
         */
        private List<NodeConfig> slaves = new ArrayList<>();
        /**
         * 从库间负载均衡：round_robin | random（阶段 3 生效）
         */
        private String loadBalance = "round_robin";
    }

    /**
     * 物理节点连接配置（主从各自独立，连接池参数互不影响）
     */
    @Data
    public static class NodeConfig {
        private String driverClassName;
        private String url;
        private String username;
        private String password;
        private ConnectionConfig connection = new ConnectionConfig();
    }

    /**
     * 单个逻辑表的分片规则：db 段与 table 段至少配置其一
     */
    @Data
    public static class ShardingRuleConfig {
        private DbRuleConfig db;
        private TableRuleConfig table;
    }

    /**
     * 分库段：分片范围 + 分库键 + 算法
     */
    @Data
    public static class DbRuleConfig {
        /**
         * 参与分片的逻辑数据源列表（长度即分库数）
         */
        private List<String> databases = new ArrayList<>();
        private String shardingKey;
        private String algorithm = "hash";
        private Map<String, String> algorithmParams = new LinkedHashMap<>();
    }

    /**
     * 分表段：分表数量 + 分表键 + 算法
     */
    @Data
    public static class TableRuleConfig {
        private int tableCount;
        private String shardingKey;
        private String algorithm = "hash";
        private Map<String, String> algorithmParams = new LinkedHashMap<>();
    }

    /**
     * 连接池参数（节点级，缺省用内置默认值）
     */
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
