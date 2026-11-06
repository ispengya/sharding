package com.ispengya.sharding.config;

import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.config.ShardingProperties;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

public class DbConfigValidator {

    public static void validate(String dsName, ShardingProperties.DataSourceConfig dataSourceConfig) {
        checkNotBlank(dataSourceConfig.getDriverClassName(), dsName + " driverClassName cannot be empty");
        checkNotBlank(dataSourceConfig.getUrl(), dsName + "url cannot be empty");
        checkNotBlank(dataSourceConfig.getUsername(), dsName + "username cannot be empty");
        checkNotBlank(dataSourceConfig.getPassword(), dsName + "password cannot be empty");
    }

    private static void checkNotBlank(String value, String message) {
        if (StringUtils.isBlank(value)) {
            throw new ShardingException(message);
        }
    }
}