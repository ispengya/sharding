# 性能分析

性能测试分两层：**JMH 微基准**（不依赖数据库，量路由/改写开销）与**真库端到端压测**（量 TPS/延迟/事务模式代价，
并在负载下做数据一致性校验）。本文档记录方法、环境与基线数据。

## 微基准

### 工程结构

| 位置 | 说明 |
|---|---|
| `src/perf/java/com/ispengya/sharding/perf/` | JMH 基准类；**不在默认构建里**，仅 `perf` profile 编译 |
| `pom.xml` 的 `perf` profile | JMH 1.37 + `build-helper` 把 `src/perf/java` 加入源码目录 |
| `scripts/perf.sh` | JDK 8 构建 → `dependency:build-classpath` → `java -cp` 运行 JMH → 结果落 `target/jmh-result/jmh.json` |

```bash
bash scripts/perf.sh                                  # 全部基准
bash scripts/perf.sh 'RewriterBenchmark'              # 按类过滤
bash scripts/perf.sh '-t 2'                           # 透传 JMH 参数
```

### 基准清单

| 基准 | 覆盖 |
|---|---|
| `RewriterBenchmark` | `ShardingRewriter.plan()`：分表点查 / INSERT（解析+改写+路由）、无规则表（解析不改写）、无规则快路径（不解析） |
| `KeyExtractorBenchmark` | 键提取：Map 精确命中、POJO 反射、Map 值中 POJO 兜底、字符串直取 |
| `RouteResolveBenchmark` | 路由链：MASTER、SLAVE + round_robin、primary 兜底 |

### 环境（2026-11-09 基线）

- Windows 10，JDK 1.8.0_351（HotSpot 64-Bit Server VM 25.351-b10），JMH 1.37
- 单线程（`@Threads` 默认 1）、`@Fork(1)`、预热 3×1s、测量 5×1s、AverageTime
- **绝对数仅在本机可比**，跨机器请只比较相对差值

### 首批结果

| Benchmark | 结果 | 说明 |
|---|---|---|
| `RewriterBenchmark.fastPathNoRules` | 0.011 µs/op | 无规则直通，不解析 |
| `RewriterBenchmark.unshardedSelect` | 0.755 µs/op | 有规则配置时，无规则表仍要解析 |
| `RewriterBenchmark.insert` | 0.935 µs/op | 解析 + 表名改写 + 分库路由 |
| `RewriterBenchmark.pointSelect` | 1.053 µs/op | 同上，SELECT |
| `KeyExtractorBenchmark.stringSource` | 2.2 ns/op | 字符串直取 |
| `KeyExtractorBenchmark.mapLookup` | 6.6 ns/op | ParamMap 精确命中 |
| `KeyExtractorBenchmark.pojoReflection` | 124 ns/op（误差大，待复测） | 每调用全链反射 |
| `KeyExtractorBenchmark.mapFallbackPojo` | 163 ns/op（误差大） | Map 未命中后遍历值反射 |
| `RouteResolveBenchmark.master` | 14 ns/op | 可忽略 |
| `RouteResolveBenchmark.primaryFallback` | 14 ns/op | 可忽略 |
| `RouteResolveBenchmark.slaveRoundRobin` | 55 ns/op | 可忽略 |

## 端到端压测

### 工程结构

| 位置 | 说明 |
|---|---|
| `src/test/java/com/ispengya/sharding/perf/E2ePerfHarness.java` | 压测 harness：复用集成测试 Spring 装配，多线程固定时长负载，HdrHistogram 记 p50~p999；每场景结束后做一致性校验 |
| `scripts/perf-e2e.sh` | 起三节点 MySQL → 编译 → 以 test classpath 运行 harness |
| `PlainMapper#findPlain` 等 | 新增的性能基座查询（无规则表直读） |

```bash
bash scripts/perf-e2e.sh                                                  # 全部场景，8 线程
bash scripts/perf-e2e.sh --scenario=shard-read --threads=1,8,32,64 --duration=10
bash scripts/perf-e2e.sh --scenario=mixed-rw,tx-xa --threads=8 --warmup=10 --duration=600 --progress=30
```

参数：`--scenario`（all 或逗号列表，见下）、`--threads`（支持梯度 `1,8,32`）、`--warmup`（秒）、
`--duration`（秒）、`--progress`（秒，0 关闭中间进度输出）。

场景：

| key | 说明 |
|---|---|
| `jdbc-read` | 裸 JDBC/Druid 下限（非同栈对照） |
| `plain-read` | MyBatis + 中间件，无规则表直读 primary |
| `shard-read` | 单分片点查 |
| `slave-read` | `route = SLAVE` 轮询从库 |
| `plain-write` | 无规则表写入（primary） |
| `shard-write` | 单分片写入 |
| `mixed-rw` | 80% 读 + 20% 写混合负载 |
| `tx-local` / `tx-xa` / `tx-3pc` | 跨分片写三种协调模式 |
| `tx-local-rollback` | 跨分片写后抛异常，验证回滚不留数据 |

### 一致性校验口径

每个场景在预热前、测量后各采一次快照，结束后自动核对（FAIL 时进程退出码为 1）：

- **写入类**：`t_order` 在 ds_0 / ds_1 的行数增量 == 成功写入行数（按库分别核对）；`t_plain` 同理
- **XA / 3PC**：`sharding_tx_log` 不得残留 `BEGIN` / `PREPARED`；`COMMITTED` 增量 == 成功事务数
- **回滚场景**：行数增量必须为 0

> 若从库容器 unhealthy（旧卷里的 relay log 失效），执行
> `docker compose -f src/test/resources/docker-compose.yml down -v` 后重跑。

### 基线（8 线程，预热 5s/测量 15s，2026-11-09）

| 场景 | TPS | p50 | p95 | p99 | p999 | 错误 |
|---|---:|---:|---:|---:|---:|---:|
| jdbc-read（裸 JDBC 下限） | 12389 | 0.62ms | 0.93ms | 1.15ms | 2.62ms | 0 |
| plain-read（无规则表，primary） | 5960 | 1.30ms | 1.76ms | 2.20ms | 3.85ms | 0 |
| shard-read（单分片点查） | 5844 | 1.33ms | 1.79ms | 2.25ms | 3.55ms | 0 |
| shard-write（单分片写入） | 1808 | 3.81ms | 7.71ms | 8.95ms | 18.30ms | 0 |
| slave-read（SLAVE 轮询） | 5871 | 1.32ms | 1.79ms | 2.25ms | 3.91ms | 0 |
| tx-local（跨分片写） | 613 | 12.68ms | 15.83ms | 18.73ms | 36.08ms | 0 |
| tx-xa（跨分片写） | 204 | 38.96ms | 45.09ms | 52.00ms | 79.76ms | 0 |
| tx-3pc（跨分片写） | 190 | 41.39ms | 49.94ms | 56.16ms | 63.83ms | 0 |

### 线程梯度（预热 3s/测量 10s）

| 场景 | 1 线程 | 8 线程 | 32 线程 | 64 线程 |
|---|---:|---:|---:|---:|
| shard-read TPS | 641 | 4868 | 9775 | 10064 |
| shard-read p99 | 5.09ms | 3.21ms | 6.69ms | 16.23ms |
| shard-write TPS | 309 | 1471 | 3702 | 4155 |
| shard-write p99 | 5.39ms | 11.17ms | 19.19ms | 47.19ms |

读在 32 线程接近饱和（~10k TPS），写在 32 线程 ~3.7k、64 线程 ~4.2k；延迟随并发上升但无等待、无错误。

### 长稳（8 线程，预热 10s/测量 150s，2026-11-09）

| 场景 | TPS | p50 | p99 | p999 | 一致性 |
|---|---:|---:|---:|---:|---|
| mixed-rw | 3876 | 1.34ms | 8.59ms | 20.32ms | PASS（ds_0 60991 / ds_1 60992 行精确匹配） |
| tx-local | 616 | 12.26ms | 26.95ms | 36.21ms | PASS（两库各 98362 行） |
| tx-xa | 200 | 38.54ms | 69.60ms | 119.80ms | PASS（行精确匹配；txlog dangling=0，COMMITTED +31575/31575） |
| tx-3pc | 192 | 39.58ms | 71.17ms | 93.45ms | PASS（同上，COMMITTED +30778/30778） |

长稳期间每 30s 采样：实例 TPS 平稳（mixed-rw 3.7k~4.1k、tx-local ~615、XA ~195-207、3PC ~189-193），
堆内存无增长趋势，连接池 `wait=0`，无错误累积。

## 结论

1. **路由/解析开销在端到端不可见**：`shard-read` 与 `plain-read`（仅差"是否分片改写"）TPS 差 <2%，
   落在噪声内；微基准 ~1 µs 的解析成本相对 ~1 ms 的 DB 往返可忽略。**暂不做解析缓存**。
2. **MyBatis + Spring + 中间件栈相对裸 JDBC 约 2 倍开销**（5960 vs 12389 TPS），是框架固定成本，
   不是分片引入的。
3. **事务模式代价**：跨分片写 LOCAL ~615 TPS；XA ~200 TPS（约 LOCAL 的 1/3，prepare/commit 往返 + 日志落盘）；
   3PC ~192 TPS（比 XA 再低约 4%，多一个 canCommit 探针与阶段调度）。
4. **XA 的连接占用**：每个跨分片事务在 prepare 到 commit 期间各占一个分片连接；8 并发时
   `ds_1#master` 达到 maxActive（active=8、pooling=0）。容量规划按
   `并发跨分片事务数 × 分片数` 设置各池 maxActive。
5. **负载下数据一致**：长稳 150s 的行数、事务日志全部精确匹配，无悬挂分支、无错误累积；
   回滚场景在负载下不残留数据。
6. SLAVE 轮询读与主库读持平（无主从延迟场景），负载均衡本身无感。

## 待补

- 更长窗口（>10 分钟）与 GC/CPU 采样（当前只记堆使用量）
- 主从延迟场景（写后立即读从库）与 `slave-read` 的一致性边界
- 故障注入：压测中强杀 MySQL / 切断分片，验证 2PC/3PC 恢复与 LOCAL 重试明细
- 公平的"同栈无中间件"基线（当前 `jdbc-read` 是裸 JDBC 下限，非同栈对照）
