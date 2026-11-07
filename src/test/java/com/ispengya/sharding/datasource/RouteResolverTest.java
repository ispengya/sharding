package com.ispengya.sharding.datasource;

import com.ispengya.sharding.annotation.Route;
import com.ispengya.sharding.config.ShardingProperties;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.loadbalance.LoadBalances;
import com.ispengya.sharding.loadbalance.LoadBalancer;
import com.ispengya.sharding.loadbalance.RandomLoadBalancer;
import com.ispengya.sharding.loadbalance.RoundRobinLoadBalancer;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * 路由链测试：分片选数据源 → 组内选角色；
 * MASTER/未设置走 master、SLAVE 走从库轮询、无 slaves 降级 master、非法负载均衡策略启动报错。
 */
public class RouteResolverTest {

    private RouteResolver resolver;

    @Before
    public void setUp() {
        ShardingProperties properties = new ShardingProperties();
        properties.setPrimary("ds_0");

        ShardingProperties.DataSourceGroup ds0 = new ShardingProperties.DataSourceGroup();
        ds0.setMaster(new ShardingProperties.NodeConfig());
        ds0.getSlaves().add(new ShardingProperties.NodeConfig());
        ds0.getSlaves().add(new ShardingProperties.NodeConfig());
        ds0.setLoadBalance("round_robin");

        ShardingProperties.DataSourceGroup ds1 = new ShardingProperties.DataSourceGroup();
        ds1.setMaster(new ShardingProperties.NodeConfig());
        // 不配 slaves：SLAVE 应降级 master

        properties.getDataSources().put("ds_0", ds0);
        properties.getDataSources().put("ds_1", ds1);

        Map<String, LoadBalancer> loadBalances = LoadBalances.collect(
                Arrays.asList((LoadBalancer) new RoundRobinLoadBalancer(), new RandomLoadBalancer()));
        resolver = new RouteResolver("ds_0", properties.getDataSources(), loadBalances);
    }

    @Test
    public void blankContextFallsBackToPrimaryMaster() {
        assertEquals("ds_0#master", resolver.resolve(null, null));
        assertEquals("ds_0#master", resolver.resolve("  ", Route.MASTER));
    }

    @Test
    public void shardedKeyPlusMasterGoesToMaster() {
        assertEquals("ds_1#master", resolver.resolve("ds_1", Route.MASTER));
        assertEquals("ds_1#master", resolver.resolve("ds_1", null));
    }

    @Test
    public void slaveRoleSelectsSlaveByLoadBalance() {
        // 单线程轮询：依次遍历组内从库
        assertEquals("ds_0#slave#0", resolver.resolve("ds_0", Route.SLAVE));
        assertEquals("ds_0#slave#1", resolver.resolve("ds_0", Route.SLAVE));
        assertEquals("ds_0#slave#0", resolver.resolve("ds_0", Route.SLAVE));
    }

    @Test
    public void slaveWithoutSlavesDegradesToMaster() {
        assertEquals("ds_1#master", resolver.resolve("ds_1", Route.SLAVE));
    }

    @Test
    public void primaryGroupSlaveAlsoRoundRobins() {
        assertEquals("ds_0#slave#0", resolver.resolve(null, Route.SLAVE));
        assertEquals("ds_0#slave#1", resolver.resolve(null, Route.SLAVE));
    }

    @Test(expected = ShardingException.class)
    public void unknownLoadBalanceFailsAtStartup() {
        ShardingProperties properties = new ShardingProperties();
        properties.setPrimary("ds_0");
        ShardingProperties.DataSourceGroup ds0 = new ShardingProperties.DataSourceGroup();
        ds0.setMaster(new ShardingProperties.NodeConfig());
        ds0.setLoadBalance("magic");
        properties.getDataSources().put("ds_0", ds0);

        new RouteResolver("ds_0", properties.getDataSources(),
                LoadBalances.collect(Arrays.asList((LoadBalancer) new RoundRobinLoadBalancer())));
    }
}
