package com.ispengya.sharding.loadbalance;

/**
 * 从库负载均衡 SPI：在组内从库中选择一个。
 * 实现类标注 @LoadBalance("名字") 注册，配置 dataSources.*.loadBalance 按名字引用。
 * 实现须线程安全（多线程并发取连接），按 group 维度自行维护均衡状态。
 */
public interface LoadBalancer {

    /**
     * @param group       逻辑数据源名（组维度，均衡状态按此隔离）
     * @param slaveCount  该组从库数量（≥1，由路由层保证）
     * @return 从库下标 [0, slaveCount)
     */
    int select(String group, int slaveCount);
}
