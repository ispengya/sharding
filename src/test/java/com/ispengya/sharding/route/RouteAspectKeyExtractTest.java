package com.ispengya.sharding.route;

import com.ispengya.sharding.annotation.DBRouter;
import com.ispengya.sharding.algorithm.HashShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingContext;
import com.ispengya.sharding.config.ShardingProperties;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.rule.ShardingRuleRegistry;
import org.apache.ibatis.annotations.Param;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * 分库键提取测试（新语义）：
 * 单字符串参数直取、@Param 精确匹配、POJO 字段、显式 key、类级注解；
 * 提取不到或规则缺失一律抛 ShardingException（已知问题 #3/#4 在阶段 2 修复后的行为）。
 */
public class RouteAspectKeyExtractTest {

    public static class Order {
        private String userId = "u-100";
        private String orderId = "o-100";
    }

    @DBRouter
    public static class ClassAnnotated {
        public void load(String userId) {
        }
    }

    public interface Fixture {
        void single(String userId);

        void annotated(@Param("userId") String userId, @Param("orderId") String orderId);

        void bean(Order order);

        void missing(@Param("foo") String foo, @Param("bar") String bar);

        @DBRouter(key = "orderId")
        void explicitKey(@Param("orderId") String orderId, @Param("userId") String userId);
    }

    private RouteAspect aspect;
    private ShardingRuleRegistry registry;

    @Before
    public void setUp() {
        ShardingProperties properties = new ShardingProperties();
        properties.setPrimary("ds_0");
        ShardingProperties.DataSourceGroup ds0 = new ShardingProperties.DataSourceGroup();
        ds0.setMaster(new ShardingProperties.NodeConfig());
        ShardingProperties.DataSourceGroup ds1 = new ShardingProperties.DataSourceGroup();
        ds1.setMaster(new ShardingProperties.NodeConfig());
        properties.getDataSources().put("ds_0", ds0);
        properties.getDataSources().put("ds_1", ds1);

        ShardingProperties.ShardingRuleConfig ruleConfig = new ShardingProperties.ShardingRuleConfig();
        ShardingProperties.DbRuleConfig dbRule = new ShardingProperties.DbRuleConfig();
        dbRule.setDatabases(Arrays.asList("ds_0", "ds_1"));
        dbRule.setShardingKey("userId");
        dbRule.setAlgorithm("hash");
        ruleConfig.setDb(dbRule);
        properties.getRules().put("t_order", ruleConfig);

        Map<String, com.ispengya.sharding.algorithm.ShardingAlgorithm> algorithms = new HashMap<String, com.ispengya.sharding.algorithm.ShardingAlgorithm>();
        algorithms.put("hash", new HashShardingAlgorithm());
        registry = new ShardingRuleRegistry(properties, algorithms);
        aspect = new RouteAspect(registry);
    }

    private String expectedDs(String value) {
        return new HashShardingAlgorithm().calculateDb(
                ShardingContext.forDb(value, Arrays.asList("ds_0", "ds_1"), null));
    }

    @Test
    public void singleStringParamReturnedAsIs() throws Exception {
        Method method = Fixture.class.getMethod("single", String.class);
        assertEquals(expectedDs("u-1"), aspect.resolveDbRoute(method, new Object[]{"u-1"}, Fixture.class));
    }

    @Test
    public void paramAnnotationMatchedPrecisely() throws Exception {
        Method method = Fixture.class.getMethod("annotated", String.class, String.class);
        // userId 命中 @Param("userId")；orderId 不参与分库键候选
        assertEquals(expectedDs("u-42"),
                aspect.resolveDbRoute(method, new Object[]{"u-42", "o-1"}, Fixture.class));
    }

    @Test
    public void beanFieldExtractedByName() throws Exception {
        Method method = Fixture.class.getMethod("bean", Order.class);
        assertEquals(expectedDs("u-100"),
                aspect.resolveDbRoute(method, new Object[]{new Order()}, Fixture.class));
    }

    @Test(expected = ShardingException.class)
    public void missingKeyThrowsInsteadOfSilence() throws Exception {
        // 多参数中既无 @Param("userId") 也无可匹配字段 → 报错（旧实现返回 "null" 的问题已修复）
        Method method = Fixture.class.getMethod("missing", String.class, String.class);
        aspect.resolveDbRoute(method, new Object[]{"whatever", "nothing"}, Fixture.class);
    }

    @Test(expected = ShardingException.class)
    public void explicitKeyMustBelongToARule() throws Exception {
        // key=orderId 是分表键而非任何分库规则的键 → 报错
        Method method = Fixture.class.getMethod("explicitKey", String.class, String.class);
        aspect.resolveDbRoute(method, new Object[]{"o-1", "u-1"}, Fixture.class);
    }

    @Test
    public void classLevelAnnotationWorks() throws Exception {
        Method method = ClassAnnotated.class.getMethod("load", String.class);
        assertEquals(expectedDs("u-7"),
                aspect.resolveDbRoute(method, new Object[]{"u-7"}, ClassAnnotated.class));
    }

    @Test(expected = ShardingException.class)
    public void noDbRulesAtAllThrows() throws Exception {
        ShardingProperties empty = new ShardingProperties();
        empty.setPrimary("ds_0");
        ShardingProperties.DataSourceGroup group = new ShardingProperties.DataSourceGroup();
        group.setMaster(new ShardingProperties.NodeConfig());
        empty.getDataSources().put("ds_0", group);
        RouteAspect noRuleAspect = new RouteAspect(new ShardingRuleRegistry(empty, registry.getAlgorithms()));

        Method method = Fixture.class.getMethod("single", String.class);
        noRuleAspect.resolveDbRoute(method, new Object[]{"u-1"}, Fixture.class);
    }
}
