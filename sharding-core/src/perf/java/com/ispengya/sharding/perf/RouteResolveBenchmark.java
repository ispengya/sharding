package com.ispengya.sharding.perf;

import com.ispengya.sharding.annotation.Route;
import com.ispengya.sharding.config.ShardingProperties;
import com.ispengya.sharding.datasource.RouteResolver;
import com.ispengya.sharding.loadbalance.LoadBalancer;
import com.ispengya.sharding.loadbalance.RandomLoadBalancer;
import com.ispengya.sharding.loadbalance.RoundRobinLoadBalancer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 路由链选取开销：分片落点 + 角色 → 物理节点 key 的耗时（预期接近零成本）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class RouteResolveBenchmark {

    private RouteResolver resolver;

    @Setup
    public void setUp() {
        ShardingProperties.DataSourceGroup ds0 = new ShardingProperties.DataSourceGroup();
        ds0.setMaster(new ShardingProperties.NodeConfig());
        ds0.setLoadBalance("round_robin");
        List<ShardingProperties.NodeConfig> slaves = new ArrayList<ShardingProperties.NodeConfig>();
        slaves.add(new ShardingProperties.NodeConfig());
        slaves.add(new ShardingProperties.NodeConfig());
        ds0.setSlaves(slaves);

        Map<String, ShardingProperties.DataSourceGroup> dataSources =
                new HashMap<String, ShardingProperties.DataSourceGroup>();
        dataSources.put("ds_0", ds0);

        Map<String, LoadBalancer> loadBalances = new HashMap<String, LoadBalancer>();
        loadBalances.put("round_robin", new RoundRobinLoadBalancer());
        loadBalances.put("random", new RandomLoadBalancer());
        resolver = new RouteResolver("ds_0", dataSources, loadBalances);
    }

    @Benchmark
    public String master() {
        return resolver.resolve("ds_0", Route.MASTER);
    }

    @Benchmark
    public String slaveRoundRobin() {
        return resolver.resolve("ds_0", Route.SLAVE);
    }

    @Benchmark
    public String primaryFallback() {
        return resolver.resolve(null, Route.MASTER);
    }
}
