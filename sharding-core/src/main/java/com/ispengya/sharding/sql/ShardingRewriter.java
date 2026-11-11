package com.ispengya.sharding.sql;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.util.JdbcConstants;
import com.ispengya.sharding.algorithm.ShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingContext;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.route.ShardingKeyExtractor;
import com.ispengya.sharding.rule.DBShardingRule;
import com.ispengya.sharding.rule.ShardingRuleRegistry;
import com.ispengya.sharding.rule.TableShardingRule;

import java.util.List;

/**
 * SQL 计划器：一次解析同时产出——
 * ① 分表改写（逻辑表 → 物理表，缺分表键抛错）；
 * ② 语句级分库路由：按这条 SQL 实际触及的表 + 这份参数现场计算（未标注解也路由），
 *    同一条 SQL 中多张分片表的路由必须一致（跨库 JOIN 不支持），取不到分库键抛错。
 * 无任何规则时走直通快路径（不解析）。
 */
public class ShardingRewriter {

    private static final DbType DB_TYPE = JdbcConstants.MYSQL;

    private final ShardingRuleRegistry registry;

    public ShardingRewriter(ShardingRuleRegistry shardingRuleRegistry) {
        this.registry = shardingRuleRegistry;
    }

    /**
     * 语句级计划结果：改写后的 SQL + 分库路由落点（null = 未触及分库规则表，
     * 调用方应保持原路由上下文——方法基线或 primary 直通）。
     */
    public static final class StatementPlan {
        private final String sql;
        private final String dbRoute;

        StatementPlan(String sql, String dbRoute) {
            this.sql = sql;
            this.dbRoute = dbRoute;
        }

        public String getSql() {
            return sql;
        }

        public String getDbRoute() {
            return dbRoute;
        }
    }

    /**
     * 生成语句计划（在 StatementHandler.prepare 选池之前调用）。
     * 解析失败且已配置规则 → 抛错（无法确定路由不静默兜底）；无规则 → 原样直通。
     */
    public StatementPlan plan(String originalSql, Object parameter) {
        if (registry.getDbRules().isEmpty() && registry.getTableRules().isEmpty()) {
            return new StatementPlan(originalSql, null);
        }
        List<SQLStatement> stmts;
        try {
            stmts = SQLUtils.parseStatements(originalSql, DB_TYPE);
        } catch (Exception e) {
            throw new ShardingException("SQL 解析失败，无法确定分片路由: " + originalSql, e);
        }
        if (stmts.isEmpty()) {
            return new StatementPlan(originalSql, null);
        }
        SQLStatement statement = stmts.get(0);

        // ① 语句级分库路由（表驱动）
        DbRouteCollectVisitor collector = new DbRouteCollectVisitor(parameter, this);
        statement.accept(collector);

        // ② 分表改写（缺分表键在此抛错）
        statement.accept(new ShardingTableVisitor(parameter, this));

        return new StatementPlan(SQLUtils.toSQLString(statement, DB_TYPE), collector.getDbRoute());
    }

    /**
     * 单个逻辑表的分库路由：无分库规则返回 null；
     * 取键名固定为该表规则的 shardingKey（注解不提供显式指定）；
     * 取不到值抛 ShardingException（语句级 fail-fast）。
     */
    public String routeForTable(String logicTable, Object parameter) {
        DBShardingRule rule = registry.getDbRules().get(logicTable);
        if (rule == null) {
            return null;
        }
        String keyName = rule.getShardingKey();
        Object value = ShardingKeyExtractor.extract(parameter, keyName);
        if (value == null) {
            throw new ShardingException("无法提取分库键 [" + keyName + "]（逻辑表 " + logicTable
                    + "）：参数中不存在对应值");
        }
        ShardingAlgorithm algorithm = registry.getAlgorithms().get(rule.getAlgorithm());
        if (algorithm == null) {
            throw new ShardingException("分库规则 " + logicTable + " 引用了不存在的算法: " + rule.getAlgorithm());
        }
        return algorithm.calculateDb(ShardingContext.forDb(value, rule.getDatabases(), rule.getAlgorithmParams()));
    }

    /**
     * 单表改写：命中分表规则改写为物理表名；无规则直通保持原名；
     * 取不到分表键直接报错（不静默跳过）。
     */
    public boolean rewrite(SQLExprTableSource x, Object parameter) {
        String logicTable = x.getExpr().toString();
        TableShardingRule rule = registry.getTableRules().get(logicTable);
        if (rule == null) {
            // 无规则直通：非分片表保持原名
            return true;
        }

        Object value = ShardingKeyExtractor.extract(parameter, rule.getShardingKey());
        if (value == null) {
            throw new ShardingException("分表键 [" + rule.getShardingKey() + "] 取不到值，无法改写逻辑表 " + logicTable);
        }

        ShardingAlgorithm algorithm = registry.getAlgorithms().get(rule.getAlgorithm());
        String actualTable = algorithm.calculateTable(
                ShardingContext.forTable(value, rule.getLogicTableName(), rule.getTableCount(), rule.getAlgorithmParams()));
        x.setExpr(new SQLIdentifierExpr(actualTable));
        return true;
    }
}
