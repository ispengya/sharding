package com.ispengya.sharding.sharding;

import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.ispengya.sharding.ShardingRouteStrategy;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

/**
 * @description:
 * @author: hanzhipeng
 * @create: 2025-04-20 13:35
 **/
public class ShardingRewriter {

    private final Set<String> logicTableNames;
    private final Map<String, ShardingRule> shardingRules;
    private final Map<String, ShardingRouteStrategy> strategyMap;

    public ShardingRewriter(ShardingRuleRegistry shardingRuleRegistry) {
        this.logicTableNames = shardingRuleRegistry.getLogicTableNames();
        this.shardingRules = shardingRuleRegistry.getShardingRules();
        this.strategyMap = shardingRuleRegistry.getStrategyMap();
    }

    public boolean rewrite(SQLExprTableSource x, Object parameter) {
        String logicTable = x.getExpr().toString();
        if (!logicTableNames.contains(logicTable)) return true;

        ShardingRule rule = shardingRules.get(logicTable);
        Object value = extractValue(rule.getShardingKey(), parameter);
        if (value == null) return true;

        String actualTable = strategyMap.get(rule.getStrategyKey()).shard(rule, value);

        x.setExpr(new SQLIdentifierExpr(actualTable));
        return true;
    }

    private Object extractValue(String field, Object param) {
        if (param instanceof Map) {
            return ((Map<?, ?>) param).get(field);
        } else {
            try {
                Field f = param.getClass().getDeclaredField(field);
                f.setAccessible(true);
                return f.get(param);
            } catch (Exception e) {
                return null;
            }
        }
    }

}
