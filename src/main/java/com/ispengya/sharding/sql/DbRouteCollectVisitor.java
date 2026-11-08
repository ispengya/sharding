package com.ispengya.sharding.sql;

import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSubqueryTableSource;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;
import com.ispengya.sharding.exception.ShardingException;

/**
 * 语句级分库路由收集器：遍历这条 SQL 触及的逻辑表，逐表按分库规则现场计算落点。
 * 同一条 SQL 中多张分片表的落点必须一致，否则抛错（跨库 JOIN 不支持）。
 * 与 ShardingTableVisitor 分开：一个只收集路由、一个只改写表名，互不干扰。
 */
class DbRouteCollectVisitor extends MySqlASTVisitorAdapter {

    private final Object parameter;
    private final ShardingRewriter rewriter;
    private String dbRoute;

    DbRouteCollectVisitor(Object parameter, ShardingRewriter rewriter) {
        this.parameter = parameter;
        this.rewriter = rewriter;
    }

    @Override
    public boolean visit(SQLExprTableSource x) {
        collect(x);
        return true;
    }

    @Override
    public boolean visit(SQLJoinTableSource x) {
        if (x.getLeft() instanceof SQLExprTableSource) {
            collect((SQLExprTableSource) x.getLeft());
        }
        if (x.getRight() instanceof SQLExprTableSource) {
            collect((SQLExprTableSource) x.getRight());
        }
        return true;
    }

    @Override
    public boolean visit(SQLSubqueryTableSource x) {
        SQLSelect subSelect = x.getSelect();
        if (subSelect != null && subSelect.getQuery() != null) {
            subSelect.getQuery().accept(this);  // 递归处理子查询
        }
        return true;
    }

    private void collect(SQLExprTableSource x) {
        String logicTable = x.getExpr().toString();
        String route = rewriter.routeForTable(logicTable, parameter);
        if (route == null) {
            return;
        }
        if (dbRoute == null) {
            dbRoute = route;
        } else if (!dbRoute.equals(route)) {
            throw new ShardingException("同一条 SQL 中分片表路由不一致（跨库 JOIN 不支持）："
                    + dbRoute + " vs " + route + "（表 " + logicTable + "）");
        }
    }

    /**
     * 计划的分库落点；null = 本条 SQL 未触及任何分库规则表
     */
    String getDbRoute() {
        return dbRoute;
    }
}
