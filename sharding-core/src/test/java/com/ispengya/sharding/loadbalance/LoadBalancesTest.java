package com.ispengya.sharding.loadbalance;

import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.testsupport.LoadBalanceFixtures.BareLoadBalancer;
import com.ispengya.sharding.testsupport.LoadBalanceFixtures.DuplicateFirst;
import com.ispengya.sharding.testsupport.LoadBalanceFixtures.DuplicateSecond;
import com.ispengya.sharding.testsupport.LoadBalanceFixtures.LoadBalancerA;
import org.junit.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 负载均衡收集器测试：按 @LoadBalance 注解名收敛、未标注/重名启动期报错。
 */
public class LoadBalancesTest {

    @Test
    public void collectsByAnnotationValue() {
        LoadBalancerA a = new LoadBalancerA();
        Map<String, LoadBalancer> map = LoadBalances.collect(Arrays.asList(a, new RoundRobinLoadBalancer()));
        assertSame(a, map.get("custom_lb_a"));
        assertTrue(map.containsKey("round_robin"));
    }

    @Test(expected = ShardingException.class)
    public void missingAnnotationThrows() {
        LoadBalances.collect(Arrays.asList((LoadBalancer) new BareLoadBalancer()));
    }

    @Test(expected = ShardingException.class)
    public void duplicateNameThrows() {
        LoadBalances.collect(Arrays.asList(
                (LoadBalancer) new DuplicateFirst(), new DuplicateSecond()));
    }
}
