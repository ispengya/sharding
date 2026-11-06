package com.ispengya.sharding.datasource;

import com.ispengya.sharding.route.RouteContextHolder;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

public class ShardingRoutingDataSource extends AbstractRoutingDataSource {

    private final String defaultDataSourceName;

    public ShardingRoutingDataSource(String defaultDataSourceName) {
        this.defaultDataSourceName = defaultDataSourceName;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        String dbKey = RouteContextHolder.getDbHolder();
        if (StringUtils.isBlank(dbKey)) {
            return defaultDataSourceName;
        }
        return dbKey;
    }

}