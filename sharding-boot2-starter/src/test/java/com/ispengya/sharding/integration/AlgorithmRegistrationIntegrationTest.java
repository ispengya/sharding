package com.ispengya.sharding.integration;

import com.ispengya.sharding.rule.ShardingRuleRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 算法注册集成测试（真实 Spring 上下文）：
 * 内置算法与 @Algorithm 注解的自定义算法均按注解名进入注册表。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApp.class)
public class AlgorithmRegistrationIntegrationTest {

    @Autowired
    private ShardingRuleRegistry registry;

    @Test
    public void builtInAlgorithmsRegisteredByAnnotationName() {
        assertNotNull(registry.getAlgorithms().get("hash"));
        assertNotNull(registry.getAlgorithms().get("range_month"));
        assertNotNull(registry.getAlgorithms().get("range_day"));
    }

    @Test
    public void annotatedCustomAlgorithmDiscoveredByComponentScan() {
        assertTrue("组件扫描应发现 @Algorithm 标注的自定义算法",
                registry.getAlgorithms().containsKey("test_custom"));
    }
}
