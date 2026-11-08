# 实现步骤

[plan.md](plan.md) 是"做什么"（清单），本文档是"怎么走"（顺序 + 每步的测试与出口标准），[journal.md](journal.md) 记"发生了什么"（日期、问题、修复）。每个阶段全部绿灯才进下一阶段。

## 测试约定

- 构建 JDK：**8**（`D:\Dev\jdk\jdk8`；`scripts/test.sh` 会自动检测切换）——JDK 17 与 Boot 2.1.3 管理的 Lombok 不兼容
- 单测：JUnit 4 + Mockito（`spring-boot-starter-test` 自带），与代码同阶段提交，不后补；不碰数据库
- 集成测：**默认打真实 MySQL 三节点**（`src/test/resources/docker-compose.yml`）：
  - `mysql-master-1` :33061 `db_1` 分片1主库（兼读写分离组的主）
  - `mysql-master-2` :33062 `db_2` 分片2主库
  - `mysql-slave` :33063 复制自 master-1（读写分离从库；`SHOW SLAVE STATUS` 双线程 + 数据同步验证过）
  - 一键：`bash scripts/test.sh`（起环境 → 等健康 → `mvn test`）；无 Docker 时只跑单测：`mvn test -Dtest='!*IntegrationTest'`
- **测试重心**：阶段 0~3 的用例围绕分库分表与读写分离设计；事务按前期假设"**写只落一个数据源**"（单数据源写回归即可），跨数据源写与 LOCAL/2PC/3PC 模式用例从各自阶段起逐个补
- 出口标准里"单测"指 `mvn test` 全绿；"集成"指对应场景的集成用例通过真库

---

## 阶段 0：测试基建与行为基线

**目标：给现有行为上保险，后面每次重构都有回归参照。**

- [x] 引入 `spring-boot-starter-test` + `h2`（test scope），搭 `src/test` 目录
- [x] 集成测试数据库：**真实 MySQL 三节点**（2 主分库分表 + 1 从读写分离，带复制并验证同步）；一键 `scripts/test.sh`；H2 方案已移除（2026-11-06 追加补强，过程见 journal）
- [x] 给**现状**写回归测试：
  - [x] 分库路由：`@DBRouter` 不同键值落到不同库（集成）
  - [x] 分表改写：SQL 被改写成物理表名（单测 + 集成落表断言）
  - [x] 分库键提取：单参数、多参数、Bean、取不到值的行为（单测）
  - [x] 事务：单库 `@Transactional` 提交/回滚正确（集成）
- [x] 记录已知问题清单：见 [journal.md](journal.md) 阶段 0"已知问题清单"，只记录不修

**出口（2026-11-06 达成）：`mvn test` 18 用例全绿，现有行为全部有测试覆盖。**

## 阶段 1：项目结构重构（纯重构，不改行为）

**目标：包结构、命名、类职责按职责分层重排，为后续阶段提供落点。阶段 0 的测试是安全网，本阶段只许改包名/类名/import/拆类，不许改任何行为断言与逻辑。**

### 现状问题

- `com.ispengya.sharding.sharding.*` —— 包名冗余嵌套，且路由/切面/SQL 改写/数据源全部平铺，按技术混杂不按职责分层
- 根包混放注解与策略接口（`DBRouter`、`DBShardingStrategy`、`TableShardingStrategy`）
- `system` 包无语义；`HashCodeDBShardingStrategy` 等命名与配置里的 `hash` 对不上
- `strategyKey` + `strategyClassName` 双字段表达一个意图
- `ShardingAutoConfiguration` 巨类：建池、注册切面/拦截器、事务管理器全堆在一起
- 切面命名 `ShardingDBAspect`、上下文 `ShardingContextHolder` 职责边界不清

### 目标结构

```
com.ispengya.sharding
├── annotation/       @DBRouter（@TxMode 阶段 5 加入）
├── algorithm/        ShardingAlgorithm 统一 SPI + ShardingContext + 内置实现（hash/range）
├── rule/             规则模型（DbShardingRule、TableShardingRule）+ ShardingRuleRegistry + 规则校验
├── route/            路由上下文（原 ShardingContextHolder）、键提取、RouteAspect（原 ShardingDBAspect）
├── sql/              ShardingMybatisInterceptor、ShardingRewriter、ShardingTableVisitor
├── datasource/       ShardingRoutingDataSource、DataSourceFactory（从 AutoConfiguration 拆出）
├── config/           ShardingProperties、ShardingAutoConfiguration（只做装配）、配置校验
├── exception/        ShardingException（原 common/error）
└── util/             ParamUtils、DbConfigValidator（校验按归属分流到 rule/config 后若剩再留）
```

### 改名映射（一次性执行）

| 现在 | 改为 |
|---|---|
| 根包 `DBShardingStrategy` / `TableShardingStrategy` | `algorithm/`（**接口合并推迟至阶段 2**，先原名移入） |
| `sharding/system/HashCodeDBShardingStrategy` 等 | `algorithm/HashCode*`（原名移入，合并随阶段 2） |
| `sharding/ShardingDBAspect` | `route/RouteAspect` |
| `sharding/ShardingContextHolder` | `route/RouteContextHolder` |
| `common/error/ShardingException` | `exception/ShardingException` |
| `sharding/*`（Rewriter/Visitor/Interceptor） | `sql/*` |
| `sharding/ShardingRoutingDataSource` | `datasource/ShardingRoutingDataSource` |
| `common/tool/DbConfigValidator` | `config/DbConfigValidator` |
| `common/tool/ParamUtils` | `util/ParamUtils` |

### 步骤

- [x] 按目标结构建包执行移动与改名（`git mv` + 脚本化替换；同包拆分导致的 6 处 import 由编译器驱动补齐）
- [x] ~~策略 SPI 统一~~ **决策变更（2026-11-06）**：接口合并 `ShardingAlgorithm` 推迟到阶段 2——`ShardingContext` 形状（databases/algorithmParams）由阶段 2 配置模型定义，现在合并只能造半成品；本阶段仅将两接口移入 `algorithm/`（理由见 journal 阶段 1）
- [x] 拆 `ShardingAutoConfiguration`：建池逻辑抽成 `datasource/DataSourceFactory`，AutoConfiguration 只剩 bean 装配
- [x] 校验器归位：`config/DbConfigValidator`（规则类校验随阶段 2 的 rules 一起做）
- [x] 更新所有 import 与反射 Class.forName 字符串；测试只改 import/类名，**断言一律未动**
- [x] 包结构说明：本节目标结构 + 文末"新增代码落位约定"表

**单测：** 阶段 0 全部 18 用例（仅 import/类型名更新）全绿
**集成：** 阶段 0 集成用例（Spring 上下文启动即 smoke）全绿
**出口（2026-11-06 达成，两批提交 `025be9f` 机械移动 + `f072efa` 职责拆分）：`mvn test` 全绿，批次一 diff 除包名/类名/文件移动外无逻辑变更**

## 阶段 2：配置模型重构（先分片）

**目标：配置定稿形状落地——`dataSources`（节点级）+ `rules`（按逻辑表、不配不分片）。**

- [x] 重写 `ShardingProperties`：`dataSources`（master/slaves/节点级 connection）、`rules`（db: databases/shardingKey/algorithm/algorithmParams + table），删除旧 `dbSharding`/`tableSharding`
- [x] 合并策略接口为 `ShardingAlgorithm` + `ShardingContext`（自阶段 1 推迟），注册统一走 Spring 容器（bean 名 = algorithm 名），删除 strategyKey/strategyClassName 双字段（内置 hash/range_month/range_day）——**2026-11-07 修订**：接口拆为 `calculateDb`/`calculateTable`，注册改为 `@Algorithm` 注解扫描（理由见 journal 阶段 2）
- [x] 启动校验扩展：`primary`/`databases` 引用存在、algorithm 存在、`databases` 非空、master 必配、tableCount>0、规则至少一段
- [x] `ShardingRuleRegistry` 重构：按逻辑表查规则；**无规则 → primary 直通**（不改写表名、不要求分片键）
- [x] `@DBRouter` 加 `key` 属性；键提取改为 `@Param` 精确匹配优先，失败抛 `ShardingException`（方法级 > 类级）
- [x] 启动校验：`@DBRouter` 出现在非 Mapper Bean → 启动报错（`DBRouterPlacementValidator`）
- [x] **预留口子**：`datasource/RouteResolver.resolve()` 收敛逻辑数据源选取（读写分离阶段只在这一处插入）
- [x] 本阶段不实现 `slaves`/`route`（配置字段已绑定，路由忽略）

**单测：** 属性/规则绑定、13 条启动校验用例、键提取 7 路径、无规则直通判断、范围算法
**集成：** ① 无规则表落 primary ② order 表按规则分片 ③ 混合场景 ④ 键取不到报错（真实 MySQL）
**出口（2026-11-07 达成，提交 `15d5be4`）：48 用例全绿；分库路由/分表改写/事务回滚等行为断言未动，已知问题 #3/#4/#5/#6/#9 相关的基线断言按 usage.md 新语义翻转（记录于 journal）**

## 阶段 3：读写分离（一写多读）

**目标：`slaves` 开关 + `route` 属性 + 负载均衡，分片与主从两层解耦。**

- [x] `dataSources` 每个物理节点独立创建 Druid 池（master 与各 slave 参数互不影响，连接池 key `{组}#master` / `{组}#slave#{下标}`）
- [x] 路由链定型：**分片选数据源 → 组内选角色**（`RouteResolver` 实例化，`ShardingRoutingDataSource` 委托，物理 key 单层路由）
- [x] 读写判定：写永远 master；读默认 master；`route = SLAVE` 走从库（`@DBRouter.route` + `RouteContextHolder` 角色，方法级 > 类级）
- [x] 负载均衡 SPI（`loadbalance/` 包 `LoadBalancer` + `@LoadBalance` 注册）+ `round_robin` / `random` 实现，与 `algorithm/` 同套路
- [x] 校验：`route = SLAVE` 在活动事务内 → 抛 `ShardingException`（切面入口 `isActualTransactionActive`）
- [x] 校验：该数据源无 slaves 时 `SLAVE` → 按 master 处理（文档已定，不报错）
- [x] 启动校验：`loadBalance` 引用的策略不存在 → 启动报错（`RouteResolver` 构造期）
- [x] 语义修订：未配置分库规则时 `@DBRouter` 不再报错——db 直通 primary，仅按 route 做角色路由（支撑 usage.md 场景②，见 journal）

**单测：** 负载均衡分布、route 解析与优先级（方法>类）、事务内 SLAVE 报错、无 slaves 降级
**集成：** ① 只配 slaves 不配 rules 的读写分流（手工装配，取舍见 journal）② 分片组内主从 ③ 主库写从库读数据正确性
**出口（2026-11-07 达成）：阶段 0/1/2 回归全绿 + 新用例全绿，`mvn test` 84 用例全绿**

## 阶段 4：包装式多连接（事务基础）

**目标：一个事务可以触达多个数据源——逻辑连接内部 1:N，为协调策略铺路。**

- [x] `ShardingConnection implements Connection`：按每条 SQL 当时的路由上下文取物理连接（复用 `RouteResolver` 选取口），落 `datasource/` 包
- [x] 懒加载 + **事务设置回填**：新取的物理连接补 `autoCommit(false)`、隔离级别、readOnly；getter 未取连接前答池默认（对齐原 `LazyConnectionDataSourceProxy` 语义，`doBegin` 无感）
- [x] enlist 登记：`getTouchedNodeKeys` / `getWrittenNodeKeys`（语句创建时捕获节点 key，`executeUpdate/execute/executeBatch` 打写标记，不解析 SQL）
- [x] `close()` 幂等，归还全部物理连接到各自池
- [x] `ShardingDataSource.getConnection()` 每次返回**新**包装连接（保证 `REQUIRES_NEW` 语义不串台）
- [x] 单分片 enlist → 保持原生提交路径（快速路径）；多分片循环提交/回滚（阶段 5 `LocalCommitStrategy` 占位）
- [x] 与 Spring 对接：事务管理器绑定的仍是逻辑连接；`LazyConnectionDataSourceProxy` 与 `ShardingRoutingDataSource` 退役
- [x] savepoint 边界：单物理连接委托原生（与改造前一致），跨分片拒绝（阶段 5 正式禁用 NESTED）

**单测：** 懒加载时机、回填正确性（mock 物理连接验证 `setAutoCommit(false)`）、enlist 名单与写标记、close 幂等与连接归还、连续两次 `getConnection()` 返回不同实例（`ShardingConnectionTest` 15 用例）
**集成：** ① 单分片事务提交/回滚与阶段 0 基线一致 ② 一个事务写两个数据源 → 循环提交两行都落库 / 回滚两行都撤销 ③ 外层回滚不影响 `REQUIRES_NEW` 内层已提交数据（`MultiDatasourceTxIntegrationTest`）
**出口（2026-11-07 达成）：回归全绿——`mvn test` 103 用例全绿，单分片路径行为与改造前完全一致**

## 阶段 5：LOCAL 模式 + `@TxMode`

**目标：第一种协调策略可用，跨数据源写的使用规则落地。**

- [x] `tx/` 包：`TxCommitStrategy` SPI（`prepare / commit / rollback`，LOCAL 的 prepare 为接口 default 空实现）+ 按 `getMode()` 收敛注册（`ShardingDataSource` 持有 Mode → 策略表，模式重复启动报错）
- [x] `LocalCommitStrategy`：单分片原生快速路径（不重试，与单库事务一致）+ 多分片循环提交
- [x] commit 部分失败：每节点重试 3 次 → 明细日志（已提交/未提交数据源 + 错误日志）+ `ShardingException` 携带明细与 cause
- [x] rollback 循环（含重试与明细）
- [x] `annotation/TxMode`（`Mode.LOCAL/XA/THREE_PC`）+ 切面（`tx/TxModeAspect`，事务拦截器外层），方法级 > 类级；模式写入 `TxContextHolder`（入口帧设置、帧结束还原、内层不覆盖）；XA/THREE_PC 提交时策略未注册报"尚未实现"
- [x] 校验：事务写入 ≥2 个数据源且未标 `@TxMode` → 第二个数据源写入时抛异常（语句未执行，事务整体回滚）
- [x] 校验：跨数据源事务使用 `REQUIRES_NEW`/`NESTED` → 抛异常（REQUIRES_NEW 由挂起状态 + 跨库标记判定；NESTED 由 savepoint 状态判定；savepoint 回滚按归属连接，拒绝后不被守卫反噬）
- [x] 免标注路径确认：单数据源写、跨数据源只读、非事务写均不触发校验

**单测：** 策略选择与注解优先级、漏标注报错、多数据源写判定（写 vs 只读 vs 非事务）、重试逻辑与明细内容、未实现模式报错（`ShardingConnectionTest` 20、`LocalCommitStrategyTest` 9、`TxModeAspectTest` 7）
**集成：** ① 跨分片 LOCAL 提交成功 ② 注入某分片 commit 失败 → 重试 3 次抛明细、回滚后已交分片保留 ③ 跨分片回滚全部生效 ④ 单数据源事务无需标注正常运行 ⑤ 漏标 `@TxMode` 报错回滚 ⑥ 跨数据源只读免标注 ⑦ 外层跨库写后内层 `REQUIRES_NEW` 报错 ⑧ `NESTED` 内层写第二分片报错
**出口（2026-11-08 达成）：回归全绿 + usage.md 第 4 节规则逐条可测，`mvn test` 129 用例全绿**

## 阶段 6：2PC（`Mode.XA`）

**目标：强一致模式，含协调者与故障恢复。**

- [x] 参与者 prepare：**决策为真 XA（`XAResource` 协议），不做本地两阶段模拟**——本地模拟下参与者是普通未提交事务，协调者进程死亡即随会话回滚，PREPARED 承诺无法兑现；真 XA 的 prepared 分支以 in-doubt 持久存活于 InnoDB，恢复才有现场。池统一换 `DruidXADataSource`（继承 `DruidDataSource`，池参数不变），分支在 `ShardingConnection` 首次取物理连接时 `XA START`（先于任何 SQL）
- [x] `tx/coordinator/Coordinator`：收集 prepare 结果 → 全部成功 commit，任一失败全部 rollback；commit 中途失败日志保持 PREPARED 交恢复兑现
- [x] 事务日志持久化：状态机 `BEGIN → PREPARED → COMMITTED / ROLLED_BACK`（表 `sharding_tx_log` 落 primary 数据源，utf8mb4，**框架自动新建**）
- [x] 日志表校验：`@TxMode(XA)` 入口即 `CREATE TABLE IF NOT EXISTS` + 字符集兼容转换，失败附 DDL 报错（无建表权限但表已存在则放行）；恢复扫描遇表不存在静默跳过（未用 XA 的应用零负担）
- [x] 启动恢复：`ContextRefreshedEvent` 扫描 `PREPARED`（presumed commit）/`BEGIN`（presumed abort）日志，按 `globalId+节点key` 重建 Xid 对参与者 `XA RECOVER` 名册补裁决；参与者缺失/裁决失败保留非终态待下次重试
- [x] `TwoPhaseCommitStrategy` 接入 SPI（prepare/commit/rollback 全委托 Coordinator；XA 分支必须走完整生命周期，无跳日志的快速路径）

**单测：** 协调状态机全部迁移与 prepare 失败裁决（`CoordinatorTest` 8）、恢复决策六路（`TransactionRecoveryListenerTest` 6，mock 日志注入各状态）、Xid 确定性重建
**集成：** ① 跨分片 XA 提交两行同见 + 日志 COMMITTED + 日志表框架新建 ② 注入 prepare 失败 → 两分片全回滚 + 日志 ROLLED_BACK ③ 模拟协调者宕机（PREPARED 日志留下、强杀分支会话）→ `XA RECOVER` 验证 in-doubt 留存 → 全新装配启动恢复 → 数据提交、日志 COMMITTED、名册清空
**出口（2026-11-08 达成）：阶段 0~5 回归全绿 + 恢复用例通过，`mvn test` 140 用例全绿**

## 阶段 7：3PC（`Mode.THREE_PC`）

**目标：在 2PC 上加阶段与超时，增量实现。**

- [x] `canCommit` → `preCommit` → `commit` 三阶段接入协调流程：`Coordinator` 新增 `canCommit`（BEGIN 登记 + 分支内只读探针）与 `preCommit`（END/PREPARE → PREPARED），与 2PC 共享 `endPrepareAll`/`rollback` 等助手；日志状态机复用四态（canCommit 后崩溃 → BEGIN → 恢复 abort，语义正确）
- [x] 超时规则：`ThreePhaseCommitStrategy` 阶段调用走守护线程池限时——canCommit/preCommit 超时或失败 → 全部回滚 ROLLED_BACK（承诺点未做出，abort 安全）；**commit 超时 → 日志已在 PREPARED，按日志状态决策：保持承诺不回滚，交启动恢复兑现**；协调者侧超时决策即启动恢复（PREPARED→补 commit、BEGIN→补 rollback，阶段 6 已实现）
- [x] `ThreePhaseCommitStrategy` 接入 SPI（默认阶段超时 10s，可构造注入）
- [x] `Mode.THREE_PC` 同样基于 XA 分支（`isXaBranchNeeded` 覆盖）；`@TxMode(THREE_PC)` 入口同样校验事务日志表

**单测：** 三阶段成功链路、canCommit 拒绝→abort、preCommit 超时→abort（绝不出现 PREPARED）、commit 超时→保持 PREPARED 不回滚（`ThreePhaseCommitStrategyTest` 6、`CoordinatorTest` 扩至 11）
**集成：** ① 正常三阶段提交（Spring 全上下文，两行落库 + 日志 COMMITTED）② 参与者 canCommit 拒绝 → 全回滚 + 日志 ROLLED_BACK + 无 in-doubt ③ canCommit 阶段挂起超时（300ms）→ 收敛为 abort、名册无悬挂
**出口（2026-11-08 达成）：全量回归 + 超时用例通过，`mvn test` 158 用例全绿**

## 阶段 8：收尾与发布

- [ ] 按 usage.md 第 6 节五个场景逐一验收（作为发布检查单）
- [ ] 重写 README：旧配置键内容全部替换，指向 `docs/usage.md`
- [ ] `docker-compose.yml` 随文档完善（一主两从示例）
- [ ] 版本升至 `0.1.0`，CHANGELOG 记录破坏性变更（配置键重命名、包结构重排）
- [ ] `mvn clean install` 全量通过

**出口：** usage.md 全部场景有对应集成用例，README 与实际配置一致

---

## 依赖关系速览

```
阶段0（基线） → 阶段1（结构重构） → 阶段2（配置/分片） → 阶段3（读写分离）
                                        ↓
                          阶段4（多连接） → 阶段5（LOCAL+@TxMode） → 阶段6（2PC） → 阶段7（3PC）
                                                                        ↓
                                                              阶段8（收尾发布）
```

阶段 0~3 属于 plan.md 板块一，4~7 属于板块二，板块三（性能分析）在阶段 8 之后单独开。

## 新增代码落位约定（后续阶段遵循）

| 新增 | 落包 |
|---|---|
| `@TxMode`、`Mode` | `annotation/` |
| `@Algorithm`（分片算法注册注解） | `annotation/` |
| 协调策略、切面、协调者、事务日志 | `tx/`（协调者子包 `tx/coordinator/`） |
| `ShardingConnection`、数据源工厂 | `datasource/` |
| 负载均衡 | `loadbalance/` |
| 新算法、算法收集器 | `algorithm/` |
