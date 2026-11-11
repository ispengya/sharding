package com.ispengya.sharding.rule;

import com.ispengya.sharding.algorithm.ShardingAlgorithm;
import com.ispengya.sharding.annotation.Algorithm;
import com.ispengya.sharding.config.ShardingConfig;
import com.ispengya.sharding.exception.ShardingException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分片规则注册表：由 ShardingConfig 与算法 Bean 构建按逻辑表组织的分库/分表规则，
 * 并在构造时完成全部启动期校验（数据源引用、算法存在性、规则形状）。
 * rules 未配置时允许为空——所有逻辑表走 primary 直通。
 */
@Slf4j
@Data
public class ShardingRuleRegistry {

    private final Map<String, DBShardingRule> dbRules = new LinkedHashMap<>();
    private final Map<String, TableShardingRule> tableRules = new LinkedHashMap<>();
    private final Map<String, ShardingAlgorithm> algorithms;
    private Set<String> logicTableNames = new LinkedHashSet<>();

    public ShardingRuleRegistry(ShardingConfig properties, Map<String, ShardingAlgorithm> algorithms) {
        this.algorithms = algorithms == null ? new LinkedHashMap<String, ShardingAlgorithm>() : algorithms;
        validateDatasources(properties);
        buildRules(properties);
        log.info("Sharding rules init: dbRules={}, tableRules={}", dbRules, tableRules);
    }

    private void validateDatasources(ShardingConfig properties) {
        if (CollectionUtils.isEmpty(properties.getDataSources())) {
            throw new ShardingException("sharding.dataSources 未配置任何数据源");
        }
        String primary = properties.getPrimary();
        if (isBlank(primary)) {
            throw new ShardingException("sharding.primary 未配置");
        }
        if (!properties.getDataSources().containsKey(primary)) {
            throw new ShardingException("sharding.primary 指向未定义的数据源: " + primary);
        }
        for (Map.Entry<String, ShardingConfig.DataSourceGroup> entry : properties.getDataSources().entrySet()) {
            if (entry.getValue().getMaster() == null) {
                throw new ShardingException("数据源 " + entry.getKey() + " 未配置 master");
            }
        }
    }

    private void buildRules(ShardingConfig properties) {
        Map<String, ShardingConfig.ShardingRuleConfig> rules = properties.getRules();
        if (CollectionUtils.isEmpty(rules)) {
            return;
        }
        for (Map.Entry<String, ShardingConfig.ShardingRuleConfig> entry : rules.entrySet()) {
            String logicTable = entry.getKey();
            ShardingConfig.ShardingRuleConfig config = entry.getValue();
            if (config == null || (config.getDb() == null && config.getTable() == null)) {
                throw new ShardingException("分片规则 " + logicTable + " 必须至少配置 db 或 table 段");
            }
            if (config.getDb() != null) {
                dbRules.put(logicTable, buildDbRule(logicTable, config.getDb(), properties));
            }
            if (config.getTable() != null) {
                TableShardingRule rule = buildTableRule(logicTable, config.getTable());
                tableRules.put(logicTable, rule);
                logicTableNames.add(logicTable);
            }
        }
    }

    private DBShardingRule buildDbRule(String logicTable, ShardingConfig.DbRuleConfig config,
                                       ShardingConfig properties) {
        if (CollectionUtils.isEmpty(config.getDatabases())) {
            throw new ShardingException("分片规则 " + logicTable + " 的 db.databases 不能为空");
        }
        for (String ds : config.getDatabases()) {
            if (!properties.getDataSources().containsKey(ds)) {
                throw new ShardingException("分片规则 " + logicTable + " 引用了未定义的数据源: " + ds);
            }
        }
        if (isBlank(config.getShardingKey())) {
            throw new ShardingException("分片规则 " + logicTable + " 的 db.shardingKey 不能为空");
        }
        checkAlgorithm(logicTable, config.getAlgorithm(), true);

        DBShardingRule rule = new DBShardingRule();
        rule.setDatabases(new ArrayList<>(config.getDatabases()));
        rule.setShardingKey(config.getShardingKey());
        rule.setAlgorithm(config.getAlgorithm());
        rule.setAlgorithmParams(config.getAlgorithmParams());
        return rule;
    }

    private TableShardingRule buildTableRule(String logicTable, ShardingConfig.TableRuleConfig config) {
        if (config.getTableCount() <= 0) {
            throw new ShardingException("分片规则 " + logicTable + " 的 table.tableCount 必须大于 0");
        }
        if (isBlank(config.getShardingKey())) {
            throw new ShardingException("分片规则 " + logicTable + " 的 table.shardingKey 不能为空");
        }
        checkAlgorithm(logicTable, config.getAlgorithm(), false);

        TableShardingRule rule = new TableShardingRule();
        rule.setLogicTableName(logicTable);
        rule.setTableCount(config.getTableCount());
        rule.setShardingKey(config.getShardingKey());
        rule.setAlgorithm(config.getAlgorithm());
        rule.setAlgorithmParams(config.getAlgorithmParams());
        return rule;
    }

    private void checkAlgorithm(String logicTable, String algorithm, boolean dbSide) {
        if (isBlank(algorithm)) {
            throw new ShardingException("分片规则 " + logicTable + " 的 algorithm 不能为空");
        }
        ShardingAlgorithm instance = algorithms.get(algorithm);
        if (instance == null) {
            throw new ShardingException("分片规则 " + logicTable + " 引用了不存在的算法: " + algorithm);
        }
        // scope 校验：实例无注解（测试直接构造注册表）时跳过，存在性已由上一步保证
        Algorithm annotation = ClassUtils.getUserClass(instance).getAnnotation(Algorithm.class);
        if (annotation == null) {
            return;
        }
        if (dbSide && annotation.scope() == Algorithm.Scope.TABLE) {
            throw new ShardingException("分片规则 " + logicTable + " 的 db 段引用了仅限分表的算法: " + algorithm);
        }
        if (!dbSide && annotation.scope() == Algorithm.Scope.DB) {
            throw new ShardingException("分片规则 " + logicTable + " 的 table 段引用了仅限分库的算法: " + algorithm);
        }
    }

    /**
     * 全部已配置的分库键（按配置顺序、去重），供路由切面依次尝试提取
     */
    public List<String> getDbShardingKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (DBShardingRule rule : dbRules.values()) {
            keys.add(rule.getShardingKey());
        }
        return new ArrayList<>(keys);
    }

    /**
     * 按分库键查找规则（同键多规则时取先配置的）
     */
    public DBShardingRule findDbRuleByKey(String shardingKey) {
        for (DBShardingRule rule : dbRules.values()) {
            if (rule.getShardingKey().equals(shardingKey)) {
                return rule;
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
