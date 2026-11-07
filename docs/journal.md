# 实现日志

每个阶段"做了什么、什么时候做的、踩了什么坑、怎么修的"都记在这里。
分工：[steps.md](steps.md) 勾任务清单，本文档记过程与日期，问题修复优先记入对应阶段。

状态取值：`未开始` / `进行中` / `完成` / `受阻`

## 总览

| 阶段 | 内容 | 状态 | 开始日期 | 完成日期 |
|---|---|---|---|---|
| 0 | 测试基建与行为基线 | 完成 | 2026-11-06 | 2026-11-06 |
| 1 | 项目结构重构 | 完成 | 2026-11-06 | 2026-11-06 |
| 2 | 配置模型重构（先分片） | 完成 | 2026-11-07 | 2026-11-07 |
| 3 | 读写分离（一写多读） | 未开始 | | |
| 4 | 包装式多连接 | 未开始 | | |
| 5 | LOCAL 模式 + `@TxMode` | 未开始 | | |
| 6 | 2PC（`Mode.XA`） | 未开始 | | |
| 7 | 3PC（`Mode.THREE_PC`） | 未开始 | | |
| 8 | 收尾与发布 0.1.0 | 未开始 | | |

## 设计阶段（已完成）

### 2026-11-04

- clone 仓库到本地，盘点现状：`AbstractRoutingDataSource` + `@DBRouter` 切面 + Druid 改写，不支持跨库事务
- 确定计划三板块：产品形态 → 事务（LOCAL/2PC/3PC，明确排除本地消息表/SAGA/TCC/跨 JVM）→ 性能分析（后期）
- 决策：路由模型将从 `AbstractRoutingDataSource` 换成包装式多连接
- 提交：`docs: 新增改造计划文档`（`cdcab8e`，docs/plan.md）

### 2026-11-05

- **配置定稿**：`dataSources`（master 必配、配 slaves 即开读写分离、`connection` 节点级独立）+ `rules`（按逻辑表、`databases` 范围、**不配不分片落 primary、无默认规则**）；`dbCount` 派生自 `databases` 长度
- **使用规则定稿**：
  - `@DBRouter` 仅 Mapper 类/方法，方法级 > 类级，属性 `key` / `route`
  - `route` 默认 **MASTER**（取消 AUTO），SLAVE 只对读生效，SLAVE + 事务内 → 校验报错
  - 事务无全局配置：`@TxMode(LOCAL/XA/THREE_PC)` 方法级 > 类级；**跨数据源写未标注 → 校验报错回滚**；单数据源写、跨数据源只读免标注
  - 实施顺序：先分片后读写分离，分片阶段预留"逻辑数据源 → 物理连接"取连接口子
- 提交：`docs: 定稿使用文档与 @TxMode 事务用法`（`13cab07`，docs/usage.md + plan.md）
- 新增 docs/steps.md：9 阶段实现步骤（含阶段 1 项目结构重构）与测试出口标准

---

## 阶段 0：测试基建与行为基线

**日期：** 2026-11-06（当日完成）
**产出：** 18 个用例全绿（16 单测 + 2 集成）；`docker-compose.yml` 真实 MySQL 双分片环境（已验证）；分支 `feat/stage0-baseline`

### 问题与修复

| # | 问题 | 原因 | 修复方式 | 状态 |
|---|---|---|---|---|
| 1 | `mvn compile` 在本机 JDK 17 下报 `module jdk.compiler does not opens` | Boot 2.1.3 管理的 Lombok 1.18.6 不支持 JDK 16+ | 不改 pom，统一用 `D:\Dev\jdk\jdk8` 构建（已写入 steps.md 测试约定） | 已修复 |
| 2 | Docker 引擎未运行（Desktop 未启动），compose 方案无法落地 | 三天前正常退出后未再启动，开机未自启（服务码 1077） | 集成测试改用 H2（MySQL 模式）双内存库 `shard_0`/`shard_1`；同日启动 Docker Desktop 恢复引擎后补充 compose 并验证（建表/写入/健康检查），H2 仍为默认 | 已解决 |
| 3 | 基线断言预期"无字段 Bean 返回字符串 null"，实际返回 null | `Object.class.getSuperclass()` 为 null 触发 NPE，被切面 `catch (Exception)` 吞掉 | 修正断言并把该吞异常行为记入已知问题（阶段 2 修复） | 已修复 |
| 4 | 首版 `HashCodeStrategyBaselineTest` 编译报错且无法定位匹配（Edit 失败） | 疑似文件含不可见字符 | 整文件重写后编译通过；此后新测试文件直接整写 | 已绕过 |

### 测试基建补强（2026-11-06 追加，规划确认后执行）

**规划确认**：集成测试必须打真实 MySQL；最低 3 台（2 主测分库分表、1 从测读写分离）；前期阶段（0~3）用例围绕分库分表与读写分离，事务按"写只落一个数据源"假设，三种事务模式从 LOCAL 阶段起逐个实现逐个测。

**产出**：三节点 compose（master-1←slave 复制）+ `scripts/test.sh` 一键（起环境→等健康→mvn test）+ 集成测试默认切真库（H2 依赖与配置移除）；18 用例真库全绿；复制同步实测 1 秒内可见。

| # | 问题 | 原因 | 修复方式 | 状态 |
|---|---|---|---|---|
| 1 | Docker 引擎再次掉线 | Docker Desktop 进程中途退出 | 重新拉起并等待就绪 | 已解决 |
| 2 | `docker compose wait --wait-timeout` 报未知 flag | compose v2.31 的 `wait` 子命令不支持该参数 | 改用 `up -d --wait`（脚本同步修正） | 已解决 |
| 3 | 主库初始化死锁：执行到 `slave-init.sh` 卡死 | 主库挂了整个 `mysql-init/` 目录，复制脚本在主库上等自己的 TCP 就绪（TCP 要等初始化结束） | 初始化脚本按角色分目录：`mysql-init/master/`、`mysql-init/slave/` | 已修复 |
| 4 | 主库健康检查初始化期假阳性（socket ping 秒通） | 临时实例只开 socket | 健康检查改 TCP 探测 `mysqladmin ping -h127.0.0.1`；从库健康 = 双线程 Yes + 物理表出现 | 已修复 |
| 5 | 从库复制线程正常但永远没有 `db_1` | master 初始化期的 DDL 不在当前 binlog 段（服务重启后新文件只含启动事件），按位点复制回放不到 | 从库 init 脚本手动重建库表，再从 master 当前位点起复制；测试期行事件照常同步（实测 1 秒可见） | 已修复 |
| 6 | `--force-recreate` 后 init 脚本不执行 | 复用了旧匿名卷，MySQL 入口发现数据已初始化直接跳过 init | 加 `--renew-anon-volumes` | 已修复 |

### 已知问题清单（只记录不修，阶段 2+ 处理）

| # | 问题 | 归属阶段 |
|---|---|---|
| 1 | README 配置键过期：文档 `dbRule`/`tableRules`/`strategyClass`，代码 `dbSharding`/`tableSharding`/`strategyClassName` | 8（README 重写） |
| 2 | README hash 公式与代码不一致：README 得 `ds_1` 起，代码 `idx % dbCount` 得 `ds_0` 起——数据源命名约定以代码为准 | 8 |
| 3 | 多字符串参数取分库键得到字面量 `"null"`（`String.valueOf(null)`），静默路由到固定分片 | **已修复（阶段 2）** |
| 4 | 无对应字段的 Bean：NPE 被切面 catch 吞掉返回 null，无任何日志外的反馈 | **已修复（阶段 2）** |
| 5 | 分片键取不到时 SQL **静默不改写**，逻辑表名直发数据库，运行期才报表不存在 | **已修复（阶段 2）** |
| 6 | `tableSharding` 的 map key 不生效，必须显式配 `logicTableName` 字段，README 只展示了 map key | **已修复（阶段 2）** |
| 7 | `ConnectionConfig.testOnBorrow` 代码默认 `true`，README 参数表写 `false` | 8 |
| 8 | README 示例 SQL 使用 `order`（MySQL 保留字）作表名，照抄会语法报错 | 8 |
| 9 | `ParamUtils`：map 中无目标 key 时兜底返回 `param1`，可能取到错误语义的值 | **已修复（阶段 2）** |

---

## 阶段 1：项目结构重构

**日期：** 2026-11-06（当日完成）
**产出：** 两批提交——`025be9f` 机械移动改名、`f072efa` 职责拆分；18 用例断言零改动全绿；分支 `feat/stage1-structure`

### 问题与修复

| # | 问题 | 原因 | 修复方式 | 状态 |
|---|---|---|---|---|
| 1 | 移动后 6 处编译失败"找不到符号"（ShardingRewriter、RouteAspect、RoutingDataSource、两个测试） | 原同包类被拆到不同包，裸引用失去隐式可见性 | 按编译器报错逐个补 import（一次性定位，无遗漏风险） | 已修复 |
| 2 | `DbConfigValidator` 移入 `config/` 后包声明仍是 `common.tool` | 包声明改写清单未包含 config 目录（误以为 config 文件不动） | 补一条包声明替换；审计 grep 时抓出 | 已修复 |
| 3 | 反射 `Class.forName` 字符串不随 import 扫描暴露 | 类名以字符串形式存在于 `ShardingRuleRegistry` | FQCN 替换阶段用全量 sed 覆盖字符串；批次二直接改为 `new`，此类问题根除 | 已修复 |

### 决策变更

- **策略接口合并推迟至阶段 2**：原计划阶段 1 把 `DBShardingStrategy`/`TableShardingStrategy` 合并为 `ShardingAlgorithm + ShardingContext`。评估后放弃在阶段 1 做——`ShardingContext` 的完整形状由阶段 2 配置模型（databases/algorithmParams）定义，提前合并只能造半成品签名，且会迫使基线测试改调用写法、搅浑"只改 import"的审计标准。阶段 1 保持纯机械，阶段 2 配置切换时接口、实现、注册一次到位（已同步进 steps 阶段 1/2 清单）。

---

## 阶段 2：配置模型重构（先分片）

**日期：** 2026-11-07（当日完成）
**产出：** `15d5be4` 配置模型与 SPI 合并 + `afec24e` 类注释清理（应用户要求先行）；48 用例全绿（9 个测试类，含真实 MySQL 集成）

### 问题与修复

| # | 问题 | 原因 | 修复方式 | 状态 |
|---|---|---|---|---|
| 1 | `ContextRefreshedEvent` 编译报"找不到符号"，包名看着没错 | 该类在 `org.springframework.context.event` 包，import 写成了 `org.springframework.context`；javac 中文报错+GBK 混排一度掩盖真实符号 | 修正两处 import 为 `context.event`；后续排查编译错误先用 classpath jar 反查类的实际包路径 | 已修复 |
| 2 | `PlainMapper` 编译找不到 `DBRouter` | 阶段 1 已把 DBRouter 移到 `annotation/` 包，新测试沿用了旧根包 import | 修正 import | 已修复 |
| 3 | "键取不到"的单测设计漏洞：单字符串参数场景永远不会失败 | 新语义下单 String 参数直取（与 MyBatis 单参数一致） | 用例改为多 `@Param` 参数场景验证报错路径 | 已修复 |

### 行为变更（已知问题清单对应项，基线断言同步翻转）

- **#3** 多参数取键不再得到字面量 `"null"` → 精确匹配，失败抛异常
- **#4** 无字段 Bean 不再 NPE 吞掉返回 null → 沿父类链安全查找，找不到抛异常
- **#5** 分表键取不到不再静默保留逻辑表名 → 改写器立即抛 `ShardingException`
- **#6** `logicTableName` 字段陷阱消除 → `rules` 的 map key 即逻辑表名
- **#9** `ParamUtils` 不再兜底返回 `param1` → 仅精确匹配，取不到返回 null
- 新定义：`range_day` 语义 = `LocalDate.toEpochDay() % tableCount`（usage.md 未细化处的实现决策）
- `@DBRouter` 增加 `key` 属性；标注位置启动校验落地（接口放行、普通类报错）

### 追加决策（2026-11-07，用户反馈驱动）

阶段 2 交付后按使用反馈修订算法 API（两处痛点：`ShardingContext` 互斥字段逼实现类自行二分 db/table；"Bean 名 = 配置引用名"是隐性约定）：

- **接口拆分**：`ShardingAlgorithm.calculate` 拆为 `calculateDb` / `calculateTable` 两个方法，调用方（`RouteAspect` / `ShardingRewriter`）按规则段直调；仅支持一侧的算法在另一侧显式抛 `ShardingException`
- **注册方式变更（破坏性）**：废除 Bean 名约定，改为 `@Algorithm("名字")` 注解（元注解 `@Component`）——组件扫描发现并实例化，`ShardingAlgorithms.collect()` 按注解 value 收敛注册表；未标注/空名/重名启动报错。内置与自定义算法统一走扫描发现：自动装配加 `@ComponentScan(basePackageClasses = ShardingAlgorithm.class)` 扫框架算法包（用户应用扫描不到框架包），自定义算法随应用组件扫描发现；`shardingMeta` 从 `ApplicationContext.getBeansOfType` 收集，不再有算法 `@Bean` 方法
- **新增 `@Algorithm.scope`**（`DB` / `TABLE` / `BOTH`，默认 `BOTH`）：`range_month` 标 `DB`、`range_day` 标 `TABLE`，规则段错配在启动期报错而非首查报错；注册表实例无注解时（测试直接构造）跳过该检查
- 文档同步：usage.md 第 5 节重写、第 7 节校验清单补两行，清理 plan.md/usage.md 残留的 `strategyClassName` 表述（阶段 2 删字段时漏改）
- 测试：48 → 59 用例（新增收集器 5、scope 2、单侧拒绝 2、组件扫描发现集成 2），全绿
- 过程问题：`@ComponentScan` 扫框架算法包时连 `target/test-classes` 同包测试夹具一起扫入（Spring 按 `@Algorithm` value 当 Bean 名，重名夹具在注册阶段即 `ConflictingBeanDefinitionException`）——带 `@Algorithm` 的测试夹具必须放在被扫描包之外，已挪至 `testsupport/AlgorithmFixtures`

---

## 阶段 3：读写分离（一写多读）

**日期：** 2026-11-07（当日完成）
**产出：** 84 用例全绿（阶段 0/1/2 回归 + 新增 25：单测负载均衡 8、resolver 6、切面角色/事务 6、收集器 3，集成读写分离 5）

### 实现决策

- **路由链两层收敛于 `RouteResolver`（实例化）**：输入（dbHolder, role）→ 物理节点 key `{组}#master` / `{组}#slave#{下标}`；`targetDataSources` 直接以物理 key 注册，单层 `AbstractRoutingDataSource` 完成选择，不再嵌套路由数据源
- **角色上下文**：`RouteContextHolder` 新增 `routeHolder`（`Route` 枚举 `MASTER/SLAVE`，null 按 MASTER）；切面写入、finally 清理，与 dbHolder 对称
- **`@DBRouter.route`**：默认 MASTER，方法级 > 类级；方法上存在注解即以方法为准（裸注解可覆盖类级 SLAVE）
- **负载均衡 SPI**：接口 `LoadBalancer.select(group, slaveCount)`（均衡状态按组隔离，实现须线程安全）；注册注解 `@LoadBalance`（元注解 `@Component`），收集器 `LoadBalances.collect` 与 `ShardingAlgorithms` 同套路；自动装配 `@ComponentScan` 增扫 `loadbalance/` 包
- **事务校验时机**：切面入口（`LOWEST_PRECEDENCE - 1`，位于事务拦截器外层）`isActualTransactionActive()` → 活动事务内 `route = SLAVE` 抛 `ShardingException`
- **无 slaves 降级**：`RouteResolver` 角色层判断，`SLAVE` 且组内 0 从库 → master key（不报错）
- **启动校验**：`RouteResolver` 构造期校验每组 `loadBalance` 引用存在

### 行为变更

- **无分库规则时 `@DBRouter` 不再报错**（原：抛"未配置任何分库规则"）→ 返回 null，db 直通 primary，仅按 route 做组内角色路由。支撑 usage.md 场景②"只配 slaves 不配 rules"；基线用例 `noDbRulesAtAllThrows` 翻转为 `noDbRulesReturnsNullForPrimaryStraightThrough`

### 问题与修复

| # | 问题 | 原因 | 修复方式 | 状态 |
|---|---|---|---|---|
| 1 | 场景①"只配 slaves 不配 rules"无法在共享 Spring 上下文验证 | `application.yml` 全局带 rules，无法按测试清空 map 绑定 | `NoRulesReadWriteIntegrationTest` 手工装配 properties → 连接池 → RouteResolver → 路由数据源，直连真实 MySQL 验证 master 写 / slave 读；journal 记录该取舍 | 已解决 |
| 2 | 从库读数据正确性受异步复制延迟影响，固定断言会偶发失败 | 主从复制异步 | 轮询从库直连 JDBC 直到行可见（10s 超时），再走切面读断言 | 已解决 |

---

## 阶段 4：包装式多连接

**日期：** 未开始

### 问题与修复

| # | 问题 | 原因 | 修复方式 | 状态 |
|---|---|---|---|---|
| | | | | |

---

## 阶段 5：LOCAL 模式 + `@TxMode`

**日期：** 未开始

### 问题与修复

| # | 问题 | 原因 | 修复方式 | 状态 |
|---|---|---|---|---|
| | | | | |

---

## 阶段 6：2PC（`Mode.XA`）

**日期：** 未开始

### 问题与修复

| # | 问题 | 原因 | 修复方式 | 状态 |
|---|---|---|---|---|
| | | | | |

---

## 阶段 7：3PC（`Mode.THREE_PC`）

**日期：** 未开始

### 问题与修复

| # | 问题 | 原因 | 修复方式 | 状态 |
|---|---|---|---|---|
| | | | | |

---

## 阶段 8：收尾与发布

**日期：** 未开始

### 问题与修复

| # | 问题 | 原因 | 修复方式 | 状态 |
|---|---|---|---|---|
| | | | | |
