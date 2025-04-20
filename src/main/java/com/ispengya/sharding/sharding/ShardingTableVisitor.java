package com.ispengya.sharding.sharding;

import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSubqueryTableSource;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

public class ShardingTableVisitor extends MySqlASTVisitorAdapter {

    private final Object parameter;
    private final ShardingRewriter rewriter;

    public ShardingTableVisitor(Object parameter, ShardingRewriter rewriter) {
        this.parameter = parameter;
        this.rewriter = rewriter;
    }

    @Override
    public boolean visit(SQLExprTableSource x) {
        return rewriter.rewrite(x, parameter);
    }

    @Override
    public boolean visit(SQLJoinTableSource x) {
        if (x.getLeft() instanceof SQLExprTableSource) {
            rewriter.rewrite((SQLExprTableSource) x.getLeft(), parameter);
        }
        if (x.getRight() instanceof SQLExprTableSource) {
            rewriter.rewrite((SQLExprTableSource) x.getRight(), parameter);
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
}