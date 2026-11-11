package com.ispengya.sharding.loadbalance;

import com.ispengya.sharding.annotation.LoadBalance;
import com.ispengya.sharding.exception.ShardingException;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机负载均衡（内置，策略名 "random"）：
 * 在组内从库中等概率随机选择，无共享状态。
 */
@LoadBalance("random")
public class RandomLoadBalancer implements LoadBalancer {

    @Override
    public int select(String group, int slaveCount) {
        if (slaveCount <= 0) {
            throw new ShardingException("random 从库数量必须 ≥ 1: " + group);
        }
        return ThreadLocalRandom.current().nextInt(slaveCount);
    }
}
