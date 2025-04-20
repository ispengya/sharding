package com.ispengya.sharding.sharding;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.util.JdbcConstants;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

import java.sql.Connection;
import java.util.List;
import java.util.Properties;

/**
 * @description:
 * @author: hanzhipeng
 * @create: 2025-04-20 12:34
 **/
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})
})
public class ShardingMybatisInterceptor implements Interceptor {

    private static final DbType DB_TYPE = JdbcConstants.MYSQL;

    private final ShardingRewriter shardingRewriter;

    public ShardingMybatisInterceptor(ShardingRewriter shardingRewriter) {
        this.shardingRewriter = shardingRewriter;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler statementHandler = (StatementHandler) invocation.getTarget();
        MetaObject metaObject = SystemMetaObject.forObject(statementHandler);

        BoundSql boundSql = statementHandler.getBoundSql();
        String originalSql = boundSql.getSql();
        Object parameterObject = boundSql.getParameterObject();

        String newSql = rewriteSql(originalSql, parameterObject);
        metaObject.setValue("delegate.boundSql.sql", newSql);

        return invocation.proceed();
    }

    private String rewriteSql(String originalSql, Object param) {
        try {
            List<SQLStatement> stmts = SQLUtils.parseStatements(originalSql, DB_TYPE);
            if (stmts.isEmpty()) return originalSql;

            SQLStatement stmt = stmts.get(0);
            stmt.accept(new ShardingTableVisitor(param, shardingRewriter));
            return SQLUtils.toSQLString(stmt, DB_TYPE);
        } catch (Exception e) {
            return originalSql;
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {}
}
