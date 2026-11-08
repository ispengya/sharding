package com.ispengya.sharding.sql;

import com.ispengya.sharding.route.RouteContextHolder;
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
import java.util.Properties;

/**
 * MyBatis 拦截器：在 StatementHandler.prepare 里、选池（ShardingConnection.prepareStatement）
 * 之前完成两件事——
 * <ul>
 *   <li>调用 ShardingRewriter.plan 生成语句计划：分表改写 + 语句级分库路由
 *       （按这条 SQL 触及的表 + 这份参数现场计算，未标注解也路由）</li>
 *   <li>把计划的分库落点写入路由上下文覆盖方法基线，proceed（含选池）结束后恢复</li>
 * </ul>
 * 时机关键：此刻拿到的 Connection 还是逻辑连接，物理池在 proceed 内的
 * connection.prepareStatement 才选定——所以语句级取键来得及。
 */
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})
})
public class ShardingMybatisInterceptor implements Interceptor {

    private final ShardingRewriter shardingRewriter;

    public ShardingMybatisInterceptor(ShardingRewriter shardingRewriter) {
        this.shardingRewriter = shardingRewriter;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler statementHandler = (StatementHandler) invocation.getTarget();
        MetaObject metaObject = SystemMetaObject.forObject(statementHandler);

        BoundSql boundSql = statementHandler.getBoundSql();
        ShardingRewriter.StatementPlan plan = shardingRewriter.plan(
                boundSql.getSql(), boundSql.getParameterObject());
        metaObject.setValue("delegate.boundSql.sql", plan.getSql());

        // 分库落点按语句覆盖方法基线；未触及分库规则表则保持原上下文（基线或 primary）
        String previousDb = RouteContextHolder.getDbHolder();
        boolean overridden = plan.getDbRoute() != null;
        if (overridden) {
            RouteContextHolder.setDbHolder(plan.getDbRoute());
        }
        try {
            return invocation.proceed();
        } finally {
            if (overridden) {
                if (previousDb == null) {
                    RouteContextHolder.clearDbHolder();
                } else {
                    RouteContextHolder.setDbHolder(previousDb);
                }
            }
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
    }
}
