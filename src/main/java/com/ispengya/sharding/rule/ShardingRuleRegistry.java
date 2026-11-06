package com.ispengya.sharding.rule;

import com.ispengya.sharding.algorithm.DBShardingStrategy;
import com.ispengya.sharding.algorithm.TableShardingStrategy;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.config.ShardingProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @description:
 * @author: hanzhipeng
 * @create: 2025-04-20 12:39
 **/
@Slf4j
@Data
public class ShardingRuleRegistry {

    private Set<String> logicTableNames = new HashSet<>();
    private DBShardingRule dbShardingRule = new DBShardingRule();
    private Map<String, TableShardingRule> shardingRules = new HashMap<>();
    private Map<String, TableShardingStrategy> tableStrategyMap= new HashMap<>();
    private Map<String, DBShardingStrategy> dbStrategyMap = new HashMap<>();

    public ShardingRuleRegistry(ShardingProperties shardingProperties) {
        try {
            initTableShardingRouteRules(shardingProperties);
            log.info("Table ShardingRule init : {}", shardingRules);

            initDBShardingRouteRules(shardingProperties);
            log.info("DB ShardingRule init : {}", dbShardingRule);
        } catch (Exception e) {
            throw new ShardingException(e.getMessage());
        }
    }

    private void initDBShardingRouteRules(ShardingProperties shardingProperties) throws Exception{
        ShardingProperties.DBShardingConfig dbShardingConfig = shardingProperties.getDbSharding();
        if (dbShardingConfig == null) {
            throw new ShardingException("db Sharding config is null");
        }
        dbShardingRule.setDbCount(dbShardingConfig.getDbCount());
        dbShardingRule.setShardingKey(dbShardingConfig.getShardingKey());
        dbShardingRule.setStrategyKey(dbShardingConfig.getStrategyKey());

        loadSystemDBShardingStrategy();
    }

    private void initTableShardingRouteRules(ShardingProperties shardingProperties) throws Exception {
        Map<String, ShardingProperties.TableShardingConfig> shardingConfigMap = shardingProperties.getTableSharding();
        if (CollectionUtils.isEmpty(shardingConfigMap)) {
            throw new ShardingException("table sharding config is empty");
        }

        for (Map.Entry<String, ShardingProperties.TableShardingConfig> entry : shardingConfigMap.entrySet()) {
            TableShardingRule tableShardingRule = new TableShardingRule();
            tableShardingRule.setShardingKey(entry.getValue().getShardingKey());
            tableShardingRule.setTableCount(entry.getValue().getTableCount());
            tableShardingRule.setShardingKey(entry.getValue().getShardingKey());
            tableShardingRule.setLogicTableName(entry.getValue().getLogicTableName());
            tableShardingRule.setStrategyKey(entry.getValue().getStrategyKey());
            logicTableNames.add(entry.getValue().getLogicTableName());

            shardingRules.put(entry.getValue().getLogicTableName(), tableShardingRule);

            if (StringUtils.isNotBlank(entry.getValue().getStrategyClassName())) {
                Class<?> clazz = Class.forName(entry.getValue().getStrategyClassName());
                TableShardingStrategy strategy = (TableShardingStrategy) clazz.getDeclaredConstructor().newInstance();
                tableStrategyMap.put(entry.getValue().getStrategyKey(), strategy);
            }

            loadSystemTableShardingStrategy();
        }
    }

    private void loadSystemTableShardingStrategy() {
        try {
            Class<?> hash = Class.forName("com.ispengya.sharding.algorithm.HashCodeTableShardingStrategy");
            TableShardingStrategy hashStrategy = (TableShardingStrategy) hash.getDeclaredConstructor().newInstance();

            tableStrategyMap.put("hash", hashStrategy);
        } catch (Exception e) {
            throw new ShardingException(e.getMessage());
        }
    }

    private void loadSystemDBShardingStrategy() {
        try {
            Class<?> hash = Class.forName("com.ispengya.sharding.algorithm.HashCodeDBShardingStrategy");
            DBShardingStrategy hashStrategy = (DBShardingStrategy) hash.getDeclaredConstructor().newInstance();

            dbStrategyMap.put("hash", hashStrategy);
        } catch (Exception e) {
            throw new ShardingException(e.getMessage());
        }
    }
}
