package com.ispengya.sharding.testsupport;

import com.ispengya.sharding.annotation.LoadBalance;
import com.ispengya.sharding.loadbalance.LoadBalancer;

/**
 * 负载均衡收集器测试夹具。
 * 刻意不放在 com.ispengya.sharding.loadbalance 包：自动装配会组件扫描该包（含 test-classes），
 * 带 @LoadBalance 的夹具会被误注册进集成测试上下文（与算法夹具同因）。
 */
public final class LoadBalanceFixtures {

    private LoadBalanceFixtures() {
    }

    @LoadBalance("custom_lb_a")
    public static class LoadBalancerA implements LoadBalancer {
        @Override
        public int select(String group, int slaveCount) {
            return 0;
        }
    }

    /** 未标注 @LoadBalance 的实现 */
    public static class BareLoadBalancer implements LoadBalancer {
        @Override
        public int select(String group, int slaveCount) {
            return 0;
        }
    }

    @LoadBalance("custom_lb_dup")
    public static class DuplicateFirst implements LoadBalancer {
        @Override
        public int select(String group, int slaveCount) {
            return 0;
        }
    }

    @LoadBalance("custom_lb_dup")
    public static class DuplicateSecond implements LoadBalancer {
        @Override
        public int select(String group, int slaveCount) {
            return 1;
        }
    }
}
