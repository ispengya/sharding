package com.ispengya.sharding.sharding;

import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.ispengya.sharding.TableShardingStrategy;
import com.ispengya.sharding.common.tool.ParamUtils;

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
    private final Map<String, TableShardingRule> shardingRules;
    private final Map<String, TableShardingStrategy> strategyMap;

    public ShardingRewriter(ShardingRuleRegistry shardingRuleRegistry) {
        this.logicTableNames = shardingRuleRegistry.getLogicTableNames();
        this.shardingRules = shardingRuleRegistry.getShardingRules();
        this.strategyMap = shardingRuleRegistry.getTableStrategyMap();
    }

    public boolean rewrite(SQLExprTableSource x, Object parameter) {
        String logicTable = x.getExpr().toString();
        if (!logicTableNames.contains(logicTable)) return true;

        TableShardingRule rule = shardingRules.get(logicTable);
        Object value = ParamUtils.getParamValue(parameter, rule.getShardingKey());
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
