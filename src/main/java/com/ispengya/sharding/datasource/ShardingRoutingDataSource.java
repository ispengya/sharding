package com.ispengya.sharding.datasource;

import com.ispengya.sharding.route.RouteContextHolder;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * 路由数据源：lookup key 由 RouteResolver 计算（分片层 + 角色层），
 * targetDataSources 的 key 为物理节点 key（{组}#master / {组}#slave#{下标}）。
 */
public class ShardingRoutingDataSource extends AbstractRoutingDataSource {

    private final RouteResolver routeResolver;

    public ShardingRoutingDataSource(RouteResolver routeResolver) {
        this.routeResolver = routeResolver;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return routeResolver.resolve(RouteContextHolder.getDbHolder(), RouteContextHolder.getRoute());
    }

}
