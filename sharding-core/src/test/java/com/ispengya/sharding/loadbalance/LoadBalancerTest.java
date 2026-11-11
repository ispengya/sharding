package com.ispengya.sharding.loadbalance;

import com.ispengya.sharding.exception.ShardingException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 负载均衡策略测试：round_robin 轮询分布与分组隔离、random 边界、非法从库数报错。
 */
public class LoadBalancerTest {

    private final RoundRobinLoadBalancer roundRobin = new RoundRobinLoadBalancer();
    private final RandomLoadBalancer random = new RandomLoadBalancer();

    @Test
    public void roundRobinCyclesThroughAllSlaves() {
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 5; i++) {
                assertEquals(i, roundRobin.select("ds_0", 5));
            }
        }
    }

    @Test
    public void roundRobinCountersIsolatedPerGroup() {
        assertEquals(0, roundRobin.select("group_a", 2));
        assertEquals(0, roundRobin.select("group_b", 2));
        assertEquals(1, roundRobin.select("group_a", 2));
        assertEquals(1, roundRobin.select("group_b", 2));
    }

    @Test(expected = ShardingException.class)
    public void roundRobinRejectsZeroSlaves() {
        roundRobin.select("ds_0", 0);
    }

    @Test
    public void randomStaysWithinBounds() {
        for (int i = 0; i < 1000; i++) {
            int index = random.select("ds_0", 3);
            assertTrue("越界下标: " + index, index >= 0 && index < 3);
        }
    }

    @Test(expected = ShardingException.class)
    public void randomRejectsZeroSlaves() {
        random.select("ds_0", 0);
    }
}
