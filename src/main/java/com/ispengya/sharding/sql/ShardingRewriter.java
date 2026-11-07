package com.ispengya.sharding.sql;

import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.ispengya.sharding.algorithm.ShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingContext;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.rule.ShardingRuleRegistry;
import com.ispengya.sharding.rule.TableShardingRule;
import com.ispengya.sharding.util.ParamUtils;

/**
 * SQL 表名改写器：命中分表规则时按分表键计算物理表名并替换；
 * 未配置规则的逻辑表原样直通；配置了规则但取不到分表键时直接报错（不静默跳过）。
 */
public class ShardingRewriter {

    private final ShardingRuleRegistry registry;

    public ShardingRewriter(ShardingRuleRegistry shardingRuleRegistry) {
        this.registry = shardingRuleRegistry;
    }

    public boolean rewrite(SQLExprTableSource x, Object parameter) {
        String logicTable = x.getExpr().toString();
        TableShardingRule rule = registry.getTableRules().get(logicTable);
        if (rule == null) {
            // 无规则直通：非分片表保持原名
            return true;
        }

        Object value = ParamUtils.getParamValue(parameter, rule.getShardingKey());
        if (value == null) {
            throw new ShardingException("分表键 [" + rule.getShardingKey() + "] 取不到值，无法改写逻辑表 " + logicTable);
        }

        ShardingAlgorithm algorithm = registry.getAlgorithms().get(rule.getAlgorithm());
        String actualTable = algorithm.calculate(
                ShardingContext.forTable(value, rule.getLogicTableName(), rule.getTableCount(), rule.getAlgorithmParams()));
        x.setExpr(new SQLIdentifierExpr(actualTable));
        return true;
    }
}
