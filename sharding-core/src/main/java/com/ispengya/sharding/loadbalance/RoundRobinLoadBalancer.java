package com.ispengya.sharding.loadbalance;

import com.ispengya.sharding.annotation.LoadBalance;
import com.ispengya.sharding.exception.ShardingException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮询负载均衡（内置，策略名 "round_robin"）：
 * 按组维护独立计数器，依次轮流选择从库下标。
 */
@LoadBalance("round_robin")
public class RoundRobinLoadBalancer implements LoadBalancer {

    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<String, AtomicInteger>();

    @Override
    public int select(String group, int slaveCount) {
        if (slaveCount <= 0) {
            throw new ShardingException("round_robin 从库数量必须 ≥ 1: " + group);
        }
        AtomicInteger counter = counters.computeIfAbsent(group, key -> new AtomicInteger());
        return Math.floorMod(counter.getAndIncrement(), slaveCount);
    }
}
