package com.ispengya.sharding.perf;

import com.alibaba.druid.pool.DruidDataSource;
import com.ispengya.sharding.algorithm.HashShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingContext;
import com.ispengya.sharding.datasource.ShardingDataSource;
import com.ispengya.sharding.integration.TestApp;
import com.ispengya.sharding.integration.mapper.OrderMapper;
import com.ispengya.sharding.integration.mapper.PlainMapper;
import com.ispengya.sharding.integration.model.TOrder;
import com.ispengya.sharding.integration.model.TPlain;
import com.ispengya.sharding.integration.service.TxService;
import org.HdrHistogram.Histogram;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 端到端压测 harness（真库，不随 mvn test 运行）：
 * 复用集成测试的 Spring 装配（application.yml 三节点 MySQL），按场景跑固定时长的多线程负载，
 * 输出 TPS、p50~p999、连接池占用，并在每个场景结束后做数据一致性校验（行数 + 事务日志）。
 * 用法见 scripts/perf-e2e.sh。
 * <p>
 * 参数：--scenario=all|逗号列表 --threads=8|1,8,32 --warmup=10 --duration=60 --progress=30
 * <p>
 * 一致性校验口径：
 * - 写入类场景：场景前后物理表行数增量必须等于成功写入行数（按 ds_0 / ds_1 分别核对）；
 * - XA/3PC：事务日志不得残留 BEGIN / PREPARED，COMMITTED 增量必须等于成功事务数；
 * - 回滚场景：行数增量必须为 0。
 */
public final class E2ePerfHarness {

    private static final String JDBC_0 =
            "jdbc:mysql://localhost:33061/db_1?serverTimezone=UTC&useSSL=false&characterEncoding=utf8";
    private static final String JDBC_1 =
            "jdbc:mysql://localhost:33062/db_2?serverTimezone=UTC&useSSL=false&characterEncoding=utf8";
    private static final String USER = "root";
    private static final String PASSWORD = "root";

    private static final int READ_KEYS = 8;
    private static final String[] POOL_KEYS = {"ds_0#master", "ds_0#slave#0", "ds_1#master"};

    private enum Scenario {
        JDBC_READ("jdbc-read"),
        PLAIN_READ("plain-read"),
        SHARD_READ("shard-read"),
        SLAVE_READ("slave-read"),
        PLAIN_WRITE("plain-write"),
        SHARD_WRITE("shard-write"),
        MIXED_RW("mixed-rw"),
        TX_LOCAL("tx-local"),
        TX_XA("tx-xa"),
        TX_3PC("tx-3pc"),
        TX_LOCAL_ROLLBACK("tx-local-rollback");

        private final String key;

        Scenario(String key) {
            this.key = key;
        }

        static Scenario of(String key) {
            for (Scenario s : values()) {
                if (s.key.equalsIgnoreCase(key.trim())) {
                    return s;
                }
            }
            throw new IllegalArgumentException("未知场景: " + key + "，可选: " + Arrays.toString(values()));
        }

        boolean usesTxLog() {
            return this == TX_XA || this == TX_3PC;
        }
    }

    private interface Op {
        void execute(int threadId, long seq) throws Exception;
    }

    private final PlainMapper plainMapper;
    private final OrderMapper orderMapper;
    private final TxService txService;
    private final ShardingDataSource shardingDataSource;
    private final DruidDataSource jdbc;
    private final Map<String, String> userByDb = new HashMap<String, String>();
    private final String[] plainReadIds = new String[READ_KEYS];
    private final TOrder[] shardReadTemplates = new TOrder[READ_KEYS];

    private final AtomicLong idGen = new AtomicLong();
    private final AtomicLong writtenDb0 = new AtomicLong();
    private final AtomicLong writtenDb1 = new AtomicLong();
    private final AtomicLong writtenPlain = new AtomicLong();
    private final AtomicLong txOps = new AtomicLong();
    private final AtomicLong measuredOps = new AtomicLong();
    private final AtomicLong measuredErrors = new AtomicLong();
    private boolean consistencyFailed;

    private E2ePerfHarness(ConfigurableApplicationContext ctx) {
        this.plainMapper = ctx.getBean(PlainMapper.class);
        this.orderMapper = ctx.getBean(OrderMapper.class);
        this.txService = ctx.getBean(TxService.class);
        this.shardingDataSource = ctx.getBean(ShardingDataSource.class);
        this.jdbc = new DruidDataSource();
        this.jdbc.setUrl(JDBC_0);
        this.jdbc.setUsername(USER);
        this.jdbc.setPassword(PASSWORD);
        this.jdbc.setInitialSize(5);
        this.jdbc.setMinIdle(5);
        this.jdbc.setMaxActive(20);
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        int[] threadCounts = parseInts(options, "threads", "8");
        int warmup = Integer.parseInt(value(options, "warmup", "10"));
        int duration = Integer.parseInt(value(options, "duration", "60"));
        int progress = Integer.parseInt(value(options, "progress", "0"));
        List<Scenario> scenarios = parseScenarios(options);

        System.out.println("Booting Spring context ...");
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestApp.class)
                .web(WebApplicationType.NONE).run();
        E2ePerfHarness harness = new E2ePerfHarness(ctx);
        int exitCode;
        try {
            harness.prepare();
            for (Scenario scenario : scenarios) {
                for (int threads : threadCounts) {
                    harness.runScenario(scenario, threads, warmup, duration, progress);
                }
            }
            exitCode = harness.consistencyFailed ? 1 : 0;
        } finally {
            harness.close();
            ctx.close();
        }
        System.out.println(harness.consistencyFailed
                ? "\n结果: 存在一致性校验失败（见上方 FAIL 行）"
                : "\n结果: 全部场景通过（性能数据 + 一致性校验）");
        System.exit(exitCode);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<String, String>();
        for (String arg : args) {
            int idx = arg.indexOf('=');
            if (arg.startsWith("--") && idx > 2) {
                options.put(arg.substring(2, idx), arg.substring(idx + 1));
            }
        }
        return options;
    }

    private static String value(Map<String, String> options, String key, String fallback) {
        return options.containsKey(key) ? options.get(key) : fallback;
    }

    private static int[] parseInts(Map<String, String> options, String key, String fallback) {
        String raw = value(options, key, fallback);
        String[] parts = raw.split(",");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Integer.parseInt(parts[i].trim());
        }
        return values;
    }

    private static List<Scenario> parseScenarios(Map<String, String> options) {
        String raw = value(options, "scenario", "all");
        if ("all".equalsIgnoreCase(raw)) {
            return Arrays.asList(Scenario.values());
        }
        List<Scenario> scenarios = new ArrayList<Scenario>();
        for (String part : raw.split(",")) {
            scenarios.add(Scenario.of(part));
        }
        return scenarios;
    }

    private void prepare() throws Exception {
        jdbc.init();
        userByDb.put("ds_0", userForDb("ds_0"));
        userByDb.put("ds_1", userForDb("ds_1"));
        System.out.println("ds_0 分片键值=" + userByDb.get("ds_0") + ", ds_1 分片键值=" + userByDb.get("ds_1"));

        execute(JDBC_0, "CREATE TABLE IF NOT EXISTS t_plain (id VARCHAR(64) PRIMARY KEY, note VARCHAR(255))");
        execute(JDBC_0, "TRUNCATE TABLE t_plain");
        execute(JDBC_0, "TRUNCATE TABLE t_order_0");
        execute(JDBC_0, "TRUNCATE TABLE t_order_1");
        execute(JDBC_1, "TRUNCATE TABLE t_order_0");
        execute(JDBC_1, "TRUNCATE TABLE t_order_1");

        for (int i = 0; i < READ_KEYS; i++) {
            plainReadIds[i] = "p-read-" + i;
            plainMapper.insertPlain(new TPlain(plainReadIds[i]));

            String user = i % 2 == 0 ? userByDb.get("ds_0") : userByDb.get("ds_1");
            String orderId = "ord-read-" + i;
            String id = "o-read-" + i;
            orderMapper.insertAutoRouted(new TOrder(id, user, orderId));
            shardReadTemplates[i] = new TOrder(id, user, orderId);
        }
        // 给从库复制留出时间，避免 slave-read 读不到种子行
        Thread.sleep(1000L);
    }

    private static String userForDb(String db) {
        HashShardingAlgorithm algorithm = new HashShardingAlgorithm();
        for (int i = 0; i < 1000; i++) {
            String candidate = "u-" + i;
            String routed = algorithm.calculateDb(
                    ShardingContext.forDb(candidate, Arrays.asList("ds_0", "ds_1"), null));
            if (db.equals(routed)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到路由到 " + db + " 的分片键值");
    }

    private static void execute(String url, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void runScenario(Scenario scenario, int threads, int warmupSeconds, int durationSeconds,
                             int progressSeconds) throws Exception {
        Op op = opFor(scenario);
        System.out.println("\n=== " + scenario.key + " | threads=" + threads
                + " warmup=" + warmupSeconds + "s measure=" + durationSeconds + "s");

        writtenDb0.set(0);
        writtenDb1.set(0);
        writtenPlain.set(0);
        txOps.set(0);
        measuredOps.set(0);
        measuredErrors.set(0);

        long[] rowsBefore = countOrderRows();
        long plainBefore = countPlainRows();
        long txLogCommittedBefore = scenario.usesTxLog() ? countTxLogCommitted() : -1;

        runWarmup(op, threads, warmupSeconds);

        Histogram[] histograms = new Histogram[threads];
        long[] counts = new long[threads];
        long[] errors = new long[threads];
        String[] firstError = new String[threads];
        CountDownLatch started = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<Thread>(threads);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSeconds);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    Histogram histogram = new Histogram(1, TimeUnit.MINUTES.toNanos(1), 3);
                    long seq = 0;
                    try {
                        started.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    while (System.nanoTime() < deadline) {
                        long begin = System.nanoTime();
                        try {
                            op.execute(threadId, seq++);
                            histogram.recordValue(System.nanoTime() - begin);
                            measuredOps.incrementAndGet();
                        } catch (Throwable e) {
                            errors[threadId]++;
                            measuredErrors.incrementAndGet();
                            if (firstError[threadId] == null) {
                                firstError[threadId] = e.getClass().getSimpleName() + ": " + e.getMessage();
                            }
                        }
                    }
                    histograms[threadId] = histogram;
                    counts[threadId] = seq;
                }
            }, "perf-" + scenario.key + "-" + t);
            workers.add(worker);
            worker.start();
        }

        Thread progressReporter = startProgressReporter(progressSeconds, deadline);
        long begin = System.nanoTime();
        started.countDown();
        for (Thread worker : workers) {
            worker.join();
        }
        if (progressReporter != null) {
            progressReporter.interrupt();
            progressReporter.join(2000L);
        }
        long elapsed = System.nanoTime() - begin;

        long totalOps = 0;
        long totalErrors = 0;
        String errorSample = null;
        Histogram merged = new Histogram(1, TimeUnit.MINUTES.toNanos(1), 3);
        for (int t = 0; t < threads; t++) {
            totalOps += counts[t];
            totalErrors += errors[t];
            if (errorSample == null && firstError[t] != null) {
                errorSample = firstError[t];
            }
            if (histograms[t] != null) {
                merged.add(histograms[t]);
            }
        }

        double seconds = elapsed / 1_000_000_000.0;
        System.out.printf(
                "scenario=%s threads=%d ops=%d tps=%.1f p50=%.3fms p95=%.3fms p99=%.3fms p999=%.3fms errors=%d%n",
                scenario.key, threads, totalOps, totalOps / seconds,
                merged.getValueAtPercentile(50) / 1_000_000.0,
                merged.getValueAtPercentile(95) / 1_000_000.0,
                merged.getValueAtPercentile(99) / 1_000_000.0,
                merged.getValueAtPercentile(99.9) / 1_000_000.0,
                totalErrors);
        if (totalErrors > 0) {
            System.out.println("  首个错误: " + errorSample);
        }
        printPoolStats();
        printHeap();

        long[] rowsAfter = countOrderRows();
        long plainAfter = countPlainRows();
        verifyConsistency(scenario, rowsBefore, rowsAfter, plainBefore, plainAfter, txLogCommittedBefore);
    }

    private Thread startProgressReporter(final int intervalSeconds, final long deadline) {
        if (intervalSeconds <= 0) {
            return null;
        }
        Thread reporter = new Thread(new Runnable() {
            @Override
            public void run() {
                long lastOps = 0;
                long lastTime = System.nanoTime();
                while (System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(TimeUnit.SECONDS.toMillis(intervalSeconds));
                    } catch (InterruptedException e) {
                        return;
                    }
                    long now = System.nanoTime();
                    long ops = measuredOps.get();
                    double instTps = (ops - lastOps) / ((now - lastTime) / 1_000_000_000.0);
                    System.out.printf("[progress] ops=%d inst=%.0f/s errors=%d active=%s heap=%dMB%n",
                            ops, instTps, measuredErrors.get(), activeCounts(), heapMb());
                    lastOps = ops;
                    lastTime = now;
                }
            }
        }, "perf-progress");
        reporter.setDaemon(true);
        reporter.start();
        return reporter;
    }

    private void runWarmup(final Op op, int threads, int seconds) throws Exception {
        if (seconds <= 0) {
            return;
        }
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        List<Thread> workers = new ArrayList<Thread>(threads);
        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    long seq = 0;
                    while (System.nanoTime() < deadline) {
                        try {
                            op.execute(threadId, seq++);
                        } catch (Throwable ignored) {
                            // 预热阶段错误忽略，正式测量阶段统计
                        }
                    }
                }
            }, "perf-warmup-" + t);
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
    }

    private void verifyConsistency(Scenario scenario, long[] before, long[] after,
                                   long plainBefore, long plainAfter, long logCommittedBefore) {
        long expected0 = writtenDb0.get();
        long expected1 = writtenDb1.get();
        long actual0 = after[0] - before[0];
        long actual1 = after[1] - before[1];
        long actualPlain = plainAfter - plainBefore;
        boolean rowsOk = expected0 == actual0 && expected1 == actual1
                && writtenPlain.get() == actualPlain;

        boolean logOk = true;
        String logDetail = "";
        if (scenario.usesTxLog()) {
            long dangling = countTxLogNonTerminal();
            long committedDelta = countTxLogCommitted() - logCommittedBefore;
            logOk = dangling == 0 && committedDelta == txOps.get();
            logDetail = String.format(" txlog{dangling=%d, committed+%d/%d}", dangling, committedDelta, txOps.get());
        }

        boolean ok = rowsOk && logOk;
        if (!ok) {
            consistencyFailed = true;
        }
        System.out.printf("consistency[%s] %s t_order{ds_0 %d/%d, ds_1 %d/%d} t_plain{%d/%d}%s%n",
                scenario.key, ok ? "PASS" : "FAIL",
                actual0, expected0, actual1, expected1, actualPlain, writtenPlain.get(), logDetail);
    }

    /** 读取两个物理库的分片表总行数：[db_1, db_2] */
    private long[] countOrderRows() throws SQLException {
        return new long[]{countOrders(JDBC_0), countOrders(JDBC_1)};
    }

    private long countPlainRows() throws SQLException {
        try (Connection connection = DriverManager.getConnection(JDBC_0, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM t_plain")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long countOrders(String url) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT (SELECT COUNT(*) FROM t_order_0) + (SELECT COUNT(*) FROM t_order_1)")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long countTxLogNonTerminal() {
        return countTxLog("WHERE state IN ('BEGIN','PREPARED')");
    }

    private long countTxLogCommitted() {
        return countTxLog("WHERE state = 'COMMITTED'");
    }

    private long countTxLog(String where) {
        try (Connection connection = DriverManager.getConnection(JDBC_0, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM sharding_tx_log " + where)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            if ("42S02".equals(e.getSQLState())) {
                return 0; // 日志表尚未创建（未用 XA/3PC 的应用）
            }
            throw new IllegalStateException("查询事务日志失败: " + e.getMessage(), e);
        }
    }

    private void printPoolStats() {
        System.out.println("  pools: " + activeCounts());
    }

    private String activeCounts() {
        StringBuilder sb = new StringBuilder();
        for (String key : POOL_KEYS) {
            DataSource pool = shardingDataSource.getPool(key);
            if (pool instanceof DruidDataSource) {
                DruidDataSource druid = (DruidDataSource) pool;
                sb.append(key).append("[active=").append(druid.getActiveCount())
                        .append(",pooling=").append(druid.getPoolingCount())
                        .append(",wait=").append(druid.getWaitThreadCount()).append("] ");
            }
        }
        return sb.toString().trim();
    }

    private void printHeap() {
        System.out.println("  heap: " + heapMb() + "MB");
    }

    private long heapMb() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    }

    private Op opFor(Scenario scenario) {
        switch (scenario) {
            case JDBC_READ:
                return jdbcRead();
            case PLAIN_READ:
                return plainRead();
            case SHARD_READ:
                return shardRead();
            case SLAVE_READ:
                return slaveRead();
            case PLAIN_WRITE:
                return plainWrite();
            case SHARD_WRITE:
                return shardWrite();
            case MIXED_RW:
                return mixedRw();
            case TX_LOCAL:
                return txOp(0);
            case TX_XA:
                return txOp(1);
            case TX_3PC:
                return txOp(2);
            case TX_LOCAL_ROLLBACK:
                return txRollback();
            default:
                throw new IllegalStateException("未实现场景: " + scenario);
        }
    }

    private Op jdbcRead() {
        return new Op() {
            @Override
            public void execute(int threadId, long seq) throws Exception {
                try (Connection connection = jdbc.getConnection();
                     PreparedStatement ps = connection.prepareStatement(
                             "SELECT id FROM t_plain WHERE id = ?")) {
                    ps.setString(1, plainReadIds[(int) (seq % READ_KEYS)]);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalStateException("t_plain 未命中");
                        }
                    }
                }
            }
        };
    }

    private Op plainRead() {
        return new Op() {
            @Override
            public void execute(int threadId, long seq) {
                plainMapper.findPlain(plainReadIds[(int) (seq % READ_KEYS)]);
            }
        };
    }

    private Op shardRead() {
        return new Op() {
            @Override
            public void execute(int threadId, long seq) {
                TOrder template = shardReadTemplates[(int) (seq % READ_KEYS)];
                if (orderMapper.findId(template) == null) {
                    throw new IllegalStateException("t_order 未命中");
                }
            }
        };
    }

    private Op slaveRead() {
        return new Op() {
            @Override
            public void execute(int threadId, long seq) {
                plainMapper.findIdOnSlave(
                        plainReadIds[(int) (seq % READ_KEYS)], userByDb.get("ds_0"));
            }
        };
    }

    private Op plainWrite() {
        return new Op() {
            @Override
            public void execute(int threadId, long seq) {
                long uid = idGen.incrementAndGet();
                plainMapper.insertPlain(new TPlain("p-w-" + uid));
                writtenPlain.incrementAndGet();
            }
        };
    }

    private Op shardWrite() {
        return new Op() {
            @Override
            public void execute(int threadId, long seq) {
                long uid = idGen.incrementAndGet();
                boolean onDb0 = (uid & 1L) == 0L;
                String user = onDb0 ? userByDb.get("ds_0") : userByDb.get("ds_1");
                orderMapper.insertAutoRouted(new TOrder("w-" + uid, user, "ord-w-" + uid));
                if (onDb0) {
                    writtenDb0.incrementAndGet();
                } else {
                    writtenDb1.incrementAndGet();
                }
            }
        };
    }

    private Op mixedRw() {
        final Op read = shardRead();
        final Op write = shardWrite();
        return new Op() {
            @Override
            public void execute(int threadId, long seq) throws Exception {
                if (seq % 5 == 0) {
                    write.execute(threadId, seq);
                } else {
                    read.execute(threadId, seq);
                }
            }
        };
    }

    private Op txOp(final int mode) {
        return new Op() {
            @Override
            public void execute(int threadId, long seq) {
                long uid = idGen.incrementAndGet();
                TOrder first = new TOrder("t" + uid + "-a", userByDb.get("ds_0"), "ord-a-" + uid);
                TOrder second = new TOrder("t" + uid + "-b", userByDb.get("ds_1"), "ord-b-" + uid);
                if (mode == 0) {
                    txService.insertTwoShards(first, second);
                } else if (mode == 1) {
                    txService.insertTwoShardsXa(first, second);
                } else {
                    txService.insertTwoShardsThreePc(first, second);
                }
                txOps.incrementAndGet();
                writtenDb0.incrementAndGet();
                writtenDb1.incrementAndGet();
            }
        };
    }

    private Op txRollback() {
        return new Op() {
            @Override
            public void execute(int threadId, long seq) {
                long uid = idGen.incrementAndGet();
                TOrder first = new TOrder("r" + uid + "-a", userByDb.get("ds_0"), "ord-r-a-" + uid);
                TOrder second = new TOrder("r" + uid + "-b", userByDb.get("ds_1"), "ord-r-b-" + uid);
                try {
                    txService.insertTwoShardsThenBoom(first, second);
                } catch (IllegalStateException expected) {
                    if ("boom".equals(expected.getMessage())) {
                        return; // 事务按预期回滚：不计数写入行
                    }
                    throw expected;
                }
                throw new IllegalStateException("预期回滚但事务成功返回");
            }
        };
    }

    private void close() {
        jdbc.close();
    }
}
