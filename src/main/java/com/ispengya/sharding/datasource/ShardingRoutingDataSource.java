package com.ispengya.sharding.datasource;

import com.ispengya.sharding.route.RouteContextHolder;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

public class ShardingRoutingDataSource extends AbstractRoutingDataSource {

    private final String defaultDataSourceName;

    public ShardingRoutingDataSource(String defaultDataSourceName) {
        this.defaultDataSourceName = defaultDataSourceName;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return RouteResolver.resolve(RouteContextHolder.getDbHolder(), defaultDataSourceName);
    }

}