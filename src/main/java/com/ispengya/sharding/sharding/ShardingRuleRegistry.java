package com.ispengya.sharding.sharding;

import com.ispengya.sharding.ShardingRouteStrategy;
import com.ispengya.sharding.common.error.ShardingException;
import com.ispengya.sharding.config.ShardingProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.lang.reflect.InvocationTargetException;
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

    private int dbCount;
    private Set<String> logicTableNames;
    private Map<String, ShardingRule> shardingRules;
    private Map<String, ShardingRouteStrategy> strategyMap;

    public ShardingRuleRegistry(ShardingProperties shardingProperties) {
        try {
            initShardingRouteRules(shardingProperties);
            log.info("ShardingRuleRegistry init : {}", shardingRules);
        } catch (Exception e) {
            throw new ShardingException(e.getMessage());
        }
    }

    private void initShardingRouteRules(ShardingProperties shardingProperties)
            throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, InstantiationException,
                   IllegalAccessException {
        Map<String, ShardingProperties.ShardingConfig> shardingConfigMap = shardingProperties.getSharding();
        if (CollectionUtils.isEmpty(shardingConfigMap)) {
            throw new ShardingException("sharding rule is empty");
        }
        dbCount = shardingProperties.getDbCount();
        for (Map.Entry<String, ShardingProperties.ShardingConfig> entry : shardingConfigMap.entrySet()) {
            ShardingRule shardingRule = new ShardingRule();
            shardingRule.setDbCount(dbCount);
            shardingRule.setShardingKey(entry.getValue().getShardingKey());
            shardingRule.setTableCount(entry.getValue().getTableCount());
            shardingRule.setShardingKey(entry.getValue().getShardingKey());
            shardingRule.setLogicTableName(shardingRule.getLogicTableName());
            logicTableNames.add(entry.getValue().getLogicTableName());

            Class<?> clazz = Class.forName(entry.getValue().getStrategyClassName());
            ShardingRouteStrategy strategy = (ShardingRouteStrategy) clazz.getDeclaredConstructor().newInstance();
            strategyMap.put(entry.getValue().getStrategyKey(), strategy);
        }


    }
}
