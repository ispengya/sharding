package com.ispengya.sharding.perf;

import com.ispengya.sharding.route.ShardingKeyExtractor;
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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 分库键提取开销：Map 直取、POJO 反射、Map 值中的 POJO 兜底、字符串直取。
 * POJO 路径每次 getDeclaredField + setAccessible，是候选优化点（按类缓存 Field）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class KeyExtractorBenchmark {

    private Map<String, Object> mapParam;
    private Order pojo;
    private Map<String, Object> fallbackParam;

    public static class Order {
        private String userId = "u-1";
        private String orderId = "o-1";
    }

    @Setup
    public void setUp() {
        mapParam = new HashMap<String, Object>();
        mapParam.put("userId", "u-1");
        mapParam.put("orderId", "o-1");

        pojo = new Order();

        fallbackParam = new HashMap<String, Object>();
        fallbackParam.put("order", pojo);
        fallbackParam.put("param1", pojo);
    }

    /** MyBatis 多参数 ParamMap 精确命中。 */
    @Benchmark
    public Object mapLookup() {
        return ShardingKeyExtractor.extract(mapParam, "userId");
    }

    /** 单参数 POJO：反射取字段。 */
    @Benchmark
    public Object pojoReflection() {
        return ShardingKeyExtractor.extract(pojo, "userId");
    }

    /** ParamMap 首层未命中，遍历值中的 POJO 兜底。 */
    @Benchmark
    public Object mapFallbackPojo() {
        return ShardingKeyExtractor.extract(fallbackParam, "userId");
    }

    /** 单字符串参数：直接返回。 */
    @Benchmark
    public Object stringSource() {
        return ShardingKeyExtractor.extract("u-1", "userId");
    }
}
