package com.ispengya.sharding.integration;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 集成测试引导类：通过 spring.factories 加载 ShardingAutoConfiguration。
 */
@SpringBootApplication
@MapperScan("com.ispengya.sharding.integration.mapper")
public class TestApp {
}
