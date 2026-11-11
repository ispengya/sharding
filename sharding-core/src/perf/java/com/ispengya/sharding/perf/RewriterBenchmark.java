package com.ispengya.sharding.perf;

import com.ispengya.sharding.algorithm.HashShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingAlgorithm;
import com.ispengya.sharding.config.ShardingProperties;
import com.ispengya.sharding.rule.ShardingRuleRegistry;
import com.ispengya.sharding.sql.ShardingRewriter;
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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 语句计划开销：Druid 解析 + 分表改写 + 分库路由的每次调用成本。
 * 对照 fastPathNoRules（无规则直通，不解析）可量化"配置了规则后的固定开销"。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class RewriterBenchmark {

    private ShardingRewriter ruled;
    private ShardingRewriter fastPath;
    private Map<String, Object> params;

    @Setup
    public void setUp() {
        params = new HashMap<String, Object>();
        params.put("userId", "u-1");
        params.put("orderId", "o-1");

        Map<String, ShardingAlgorithm> algorithms = new HashMap<String, ShardingAlgorithm>();
        algorithms.put("hash", new HashShardingAlgorithm());

        ShardingProperties withRules = new ShardingProperties();
        withRules.setPrimary("ds_0");
        addGroup(withRules, "ds_0");
        addGroup(withRules, "ds_1");
        ShardingProperties.ShardingRuleConfig rule = new ShardingProperties.ShardingRuleConfig();
        ShardingProperties.DbRuleConfig db = new ShardingProperties.DbRuleConfig();
        db.setDatabases(Arrays.asList("ds_0", "ds_1"));
        db.setShardingKey("userId");
        db.setAlgorithm("hash");
        ShardingProperties.TableRuleConfig table = new ShardingProperties.TableRuleConfig();
        table.setShardingKey("orderId");
        table.setAlgorithm("hash");
        table.setTableCount(8);
        rule.setDb(db);
        rule.setTable(table);
        withRules.getRules().put("t_order", rule);
        ruled = new ShardingRewriter(new ShardingRuleRegistry(withRules, algorithms));

        ShardingProperties noRules = new ShardingProperties();
        noRules.setPrimary("ds_0");
        addGroup(noRules, "ds_0");
        fastPath = new ShardingRewriter(new ShardingRuleRegistry(noRules, algorithms));
    }

    private static void addGroup(ShardingProperties properties, String name) {
        ShardingProperties.DataSourceGroup group = new ShardingProperties.DataSourceGroup();
        group.setMaster(new ShardingProperties.NodeConfig());
        properties.getDataSources().put(name, group);
    }

    /** 分表点查：解析 + 物理表名改写 + 分库路由。 */
    @Benchmark
    public Object pointSelect() {
        return ruled.plan("SELECT id, user_id, amount FROM t_order WHERE order_id = ?", params);
    }

    /** 写入：解析 + 改写 + 路由。 */
    @Benchmark
    public Object insert() {
        return ruled.plan("INSERT INTO t_order (order_id, user_id, amount) VALUES (?, ?, ?)", params);
    }

    /** 无规则表：仍要解析（存在规则配置），但不改写、不路由。 */
    @Benchmark
    public Object unshardedSelect() {
        return ruled.plan("SELECT id FROM t_config WHERE id = ?", params);
    }

    /** 完全无规则：直通快路径，不解析。 */
    @Benchmark
    public Object fastPathNoRules() {
        return fastPath.plan("SELECT id, user_id, amount FROM t_order WHERE order_id = ?", params);
    }
}
