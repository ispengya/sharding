package com.ispengya.sharding.config;

import lombok.EqualsAndHashCode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * sharding.* 配置绑定（Spring Boot 2.x 装配层）：
 * 继承 core 的纯 POJO 模型 {@link ShardingConfig}，叠加 Boot 2.x 属性绑定注解，字段形状与历史版本完全一致。
 */
@EqualsAndHashCode(callSuper = true)
@ConfigurationProperties(prefix = ShardingProperties.DYNAMIC_DATASOURCE_PREFIX)
public class ShardingProperties extends ShardingConfig {

    public static final String DYNAMIC_DATASOURCE_PREFIX = "sharding";
}
