package com.ispengya.sharding.route;

import com.ispengya.sharding.annotation.DBRouter;
import com.ispengya.sharding.annotation.Route;
import com.ispengya.sharding.algorithm.HashShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingContext;
import com.ispengya.sharding.config.ShardingConfig;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.rule.ShardingRuleRegistry;
import org.apache.ibatis.annotations.Param;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.Before;
import org.junit.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 分库键提取测试（方法基线语义）：
 * 单字符串参数直取、@Param 精确匹配、POJO 字段、类级注解；
 * 提取不到或规则缺失一律抛 ShardingException（入口 fail-fast）。
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

    @DBRouter(route = Route.SLAVE)
    public static class ClassSlaveAnnotated {
        public void load(String userId) {
        }
    }

    @DBRouter(route = Route.SLAVE)
    public static class ClassSlaveWithMethodOverride {
        @DBRouter
        public void masterRead(String userId) {
        }
    }

    public interface Fixture {
        void single(String userId);

        void annotated(@Param("userId") String userId, @Param("orderId") String orderId);

        void bean(Order order);

        void missing(@Param("foo") String foo, @Param("bar") String bar);

        void singleLong(Long userId);

        void withMap(@Param("cond") Map<String, Object> cond, @Param("limit") Integer limit);

        @DBRouter(route = Route.SLAVE)
        void slaveRead(@Param("userId") String userId);
    }

    private RouteAspect aspect;
    private ShardingRuleRegistry registry;

    @Before
    public void setUp() {
        ShardingConfig properties = new ShardingConfig();
        properties.setPrimary("ds_0");
        ShardingConfig.DataSourceGroup ds0 = new ShardingConfig.DataSourceGroup();
        ds0.setMaster(new ShardingConfig.NodeConfig());
        ShardingConfig.DataSourceGroup ds1 = new ShardingConfig.DataSourceGroup();
        ds1.setMaster(new ShardingConfig.NodeConfig());
        properties.getDataSources().put("ds_0", ds0);
        properties.getDataSources().put("ds_1", ds1);

        ShardingConfig.ShardingRuleConfig ruleConfig = new ShardingConfig.ShardingRuleConfig();
        ShardingConfig.DbRuleConfig dbRule = new ShardingConfig.DbRuleConfig();
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

    @Test
    public void singleScalarLongParamRoutesWithoutParamAnnotation() throws Exception {
        // 修复缺口：单 Long 不加 @Param 也按标量语义路由（对齐 MyBatis 单简单参数任意名绑定）
        Method method = Fixture.class.getMethod("singleLong", Long.class);
        Long value = 42L;
        assertEquals(
                new HashShardingAlgorithm().calculateDb(
                        ShardingContext.forDb(value, Arrays.asList("ds_0", "ds_1"), null)),
                aspect.resolveDbRoute(method, new Object[]{value}, Fixture.class));
    }

    @Test
    public void mapParamInMultiArgMethodExtractsKey() throws Exception {
        // 修复缺口：多参数方法中 Map 参数携带分片键（此前 fallback 跳过 Map 值，入口即报错）
        Method method = Fixture.class.getMethod("withMap", Map.class, Integer.class);
        Map<String, Object> cond = new HashMap<>();
        cond.put("userId", "u-map");
        assertEquals(expectedDs("u-map"),
                aspect.resolveDbRoute(method, new Object[]{cond, 10}, Fixture.class));
    }

    @Test(expected = ShardingException.class)
    public void missingKeyThrowsInsteadOfSilence() throws Exception {
        // 多参数中既无 @Param("userId") 也无可匹配字段 → 报错（旧实现返回 "null" 的问题已修复）
        Method method = Fixture.class.getMethod("missing", String.class, String.class);
        aspect.resolveDbRoute(method, new Object[]{"whatever", "nothing"}, Fixture.class);
    }

    @Test
    public void classLevelAnnotationWorks() throws Exception {
        Method method = ClassAnnotated.class.getMethod("load", String.class);
        assertEquals(expectedDs("u-7"),
                aspect.resolveDbRoute(method, new Object[]{"u-7"}, ClassAnnotated.class));
    }

    @Test
    public void noDbRulesReturnsNullForPrimaryStraightThrough() throws Exception {
        // 阶段 3 语义：无分库规则不再报错——db 直通 primary，@DBRouter 仅用于 route 角色路由
        ShardingConfig empty = new ShardingConfig();
        empty.setPrimary("ds_0");
        ShardingConfig.DataSourceGroup group = new ShardingConfig.DataSourceGroup();
        group.setMaster(new ShardingConfig.NodeConfig());
        empty.getDataSources().put("ds_0", group);
        RouteAspect noRuleAspect = new RouteAspect(new ShardingRuleRegistry(empty, registry.getAlgorithms()));

        Method method = Fixture.class.getMethod("single", String.class);
        assertNull(noRuleAspect.resolveDbRoute(method, new Object[]{"u-1"}, Fixture.class));
    }

    // ---------- 读写角色（route） ----------

    @Test
    public void routeDefaultsToMasterWhenNoAnnotation() throws Exception {
        Method method = Fixture.class.getMethod("single", String.class);
        assertEquals(Route.MASTER, aspect.resolveRouteRole(method, Fixture.class));
    }

    @Test
    public void routeMethodLevelWins() throws Exception {
        Method method = Fixture.class.getMethod("slaveRead", String.class);
        assertEquals(Route.SLAVE, aspect.resolveRouteRole(method, Fixture.class));
    }

    @Test
    public void routeClassLevelAppliesWhenMethodBare() throws Exception {
        Method method = ClassSlaveAnnotated.class.getMethod("load", String.class);
        assertEquals(Route.SLAVE, aspect.resolveRouteRole(method, ClassSlaveAnnotated.class));
    }

    @Test
    public void routeMethodDefaultOverridesClassSlave() throws Exception {
        // 方法上存在注解即以方法为准（裸 @DBRouter 默认 MASTER，覆盖类级 SLAVE）
        Method method = ClassSlaveWithMethodOverride.class.getMethod("masterRead", String.class);
        assertEquals(Route.MASTER, aspect.resolveRouteRole(method, ClassSlaveWithMethodOverride.class));
    }

    @Test
    public void slaveRouteSetsContextDuringProceedAndClearsAfter() throws Throwable {
        Method method = Fixture.class.getMethod("slaveRead", String.class);
        ProceedingJoinPoint jp = mockJoinPoint(method, Fixture.class, new Object[]{"u-1"});
        when(jp.proceed()).thenAnswer(invocation -> {
            assertEquals(Route.SLAVE, RouteContextHolder.getRoute());
            // dbHolder 是分片算法算出的数据源名（ds_0/ds_1），不是键值本身
            org.junit.Assert.assertNotNull(RouteContextHolder.getDbHolder());
            return "ok";
        });

        assertEquals("ok", aspect.doRouter(jp));
        assertNull(RouteContextHolder.getRoute());
        assertNull(RouteContextHolder.getDbHolder());
    }

    @Test
    public void nestedInvocationThrows() throws Throwable {
        // 不支持注解嵌套：外层切面帧仍在时，内层入口直接报错
        Method outerMethod = Fixture.class.getMethod("slaveRead", String.class);
        ProceedingJoinPoint outer = mockJoinPoint(outerMethod, Fixture.class, new Object[]{"u-1"});
        when(outer.proceed()).thenAnswer(invocation -> {
            assertEquals(Route.SLAVE, RouteContextHolder.getRoute());
            org.junit.Assert.assertNotNull(RouteContextHolder.getDbHolder());

            Method innerMethod = Fixture.class.getMethod("annotated", String.class, String.class);
            ProceedingJoinPoint inner = mockJoinPoint(innerMethod, Fixture.class, new Object[]{"u-99", "o-99"});
            try {
                aspect.doRouter(inner);
                fail("嵌套 @DBRouter 调用应抛出 ShardingException");
            } catch (ShardingException expected) {
                // 不支持嵌套
            }

            // 报错不影响外层上下文
            assertEquals(Route.SLAVE, RouteContextHolder.getRoute());
            org.junit.Assert.assertNotNull(RouteContextHolder.getDbHolder());
            return "outer-ok";
        });

        assertEquals("outer-ok", aspect.doRouter(outer));
        assertNull(RouteContextHolder.getRoute());
        assertNull(RouteContextHolder.getDbHolder());
    }

    @Test
    public void slaveRouteInActiveTransactionThrows() throws Throwable {
        Method method = Fixture.class.getMethod("slaveRead", String.class);
        ProceedingJoinPoint jp = mockJoinPoint(method, Fixture.class, new Object[]{"u-1"});

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            aspect.doRouter(jp);
            fail("活动事务内 route = SLAVE 应当抛出 ShardingException");
        } catch (ShardingException expected) {
            // 期望的校验错误
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            RouteContextHolder.clearDbHolder();
            RouteContextHolder.clearRoute();
        }
    }

    private ProceedingJoinPoint mockJoinPoint(Method method, Class<?> targetClass, Object[] args) throws Throwable {
        ProceedingJoinPoint jp = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(jp.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(jp.getTarget()).thenReturn(targetClass);
        when(jp.getArgs()).thenReturn(args);
        when(jp.proceed()).thenReturn("ok");
        return jp;
    }
}
