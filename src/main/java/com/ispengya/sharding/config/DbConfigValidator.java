package com.ispengya.sharding.config;

import com.ispengya.sharding.exception.ShardingException;
import org.apache.commons.lang3.StringUtils;

/**
 * 物理节点连接配置校验：driverClassName/url/username/password 均必填，启动期失败快。
 */
public class DbConfigValidator {

    public static void validate(String nodeName, ShardingProperties.NodeConfig nodeConfig) {
        if (nodeConfig == null) {
            throw new ShardingException(nodeName + " 节点配置为空");
        }
        checkNotBlank(nodeConfig.getDriverClassName(), nodeName + " driverClassName cannot be empty");
        checkNotBlank(nodeConfig.getUrl(), nodeName + " url cannot be empty");
        checkNotBlank(nodeConfig.getUsername(), nodeName + " username cannot be empty");
        checkNotBlank(nodeConfig.getPassword(), nodeName + " password cannot be empty");
    }

    private static void checkNotBlank(String value, String message) {
        if (StringUtils.isBlank(value)) {
            throw new ShardingException(message);
        }
    }
}
