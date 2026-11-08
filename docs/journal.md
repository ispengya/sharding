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

**日期：** 2026-11-07（当日完成）
**产出：** 103 用例全绿（阶段 0/1/2/3 回归 + 新增 19：`ShardingConnectionTest` 15、跨数据源集成 3、场景①改写 +1）

### 实现决策

- **`ShardingDataSource` 取代 `ShardingRoutingDataSource` + `LazyConnectionDataSourceProxy`**：直接持有 `Map<物理key, 池>`，`getConnection()` 每次返回新 `ShardingConnection`；懒加载职责由逻辑连接承担（第一条语句前不碰池）
- **事务设置语义对齐 `LazyConnectionDataSourceProxy`**（读其 5.1.5 源码确认）：`DataSourceTransactionManager.doBegin` 会调 `getAutoCommit()`/`getTransactionIsolation()`——getter 未取连接前返回逻辑记录值，未记录则**懒探一次 primary 主库池默认**（启动后首次需要时），探失败兜底取当前路由连接；setter 同步回填到已取的全部物理连接
- **路由解析时机**：语句**创建时**解析并捕获节点 key（`physicalFor(key)`），既保证上下文有效，又保证写标记归属正确——execute 时不再解析（SLAVE 轮询会推进计数器导致标记错节点）
- **写标记**：语句代理拦截 `executeUpdate/execute/executeBatch/executeLargeUpdate` → `writtenNodeKeys.add(创建时key)`，不解析 SQL；MyBatis 读走 `executeQuery` 不会误标
- **提交语义**：enlist 1 个 → 原生快速路径；≥2 → 循环提交/回滚，尝试全部节点后抛首个异常（阶段 5 接管：重试 + 已交/未交明细日志）
- **savepoint 边界**：取已 enlist 的单连接（不再解析路由），≥2 个物理连接时拒绝——单分片 NESTED 与改造前一致，跨分片留给阶段 5 正式禁用
- 传播行为不受影响：`REQUIRED`/`REQUIRES_NEW` 等全部发生在 `ConnectionHolder` 层（Spring 拦截器/管理器），本阶段只换被绑定对象；集成用例外层回滚 + 内层 `REQUIRES_NEW` 已提交互不干扰验证了挂起/恢复路径

### 已知限制（阶段 5 处理）

- 多分片循环提交部分失败：本阶段"尝试全部后抛首个异常"，无重试、无已交/未交明细日志
- 跨分片写漏标 `@TxMode` 无校验（enlist 写名单已就绪）
- 跨分片 savepoint 仅运行时拒绝，无启动期/调用期统一校验入口

### 问题与修复

| # | 问题 | 原因 | 修复方式 | 状态 |
|---|---|---|---|---|
| 1 | `ShardingConnection` 编译缺 `createStruct`/`createArrayOf`/`getClientInfo`/`setTypeMap` 等方法 | `Connection` 接口方法多，逐个实现遗漏 | 按 javac 报错补齐至全部抽象方法实现 | 已解决 |
| 2 | 写标记若在 execute 时解析路由，SLAVE 轮询推进会标错节点 | `RouteResolver.resolve` 对 SLAVE 有副作用（轮询计数器） | 语句创建时解析并捕获 key，execute 只用捕获值 | 已解决 |

---

## 阶段 5：LOCAL 模式 + `@TxMode`

**日期：** 2026-11-08（当日完成）
**产出：** 129 用例全绿（前四阶段回归 + 新增 26：策略 9、切面 7、连接校验 +5、集成 5）

### 实现决策

- **提交协调挂点在 `ShardingConnection.commit/rollback`**：按 `TxContextHolder` 模式查 `TxCommitStrategy`（`ShardingDataSource` 持有 Mode → 策略表，缺省 LOCAL）——不自定义 `PlatformTransactionManager`，Spring 传播矩阵零改动（理由见对话记录：绑定资源必须是 Connection，管理器层改动仍需回调逻辑连接）
- **模式载体是 ThreadLocal 而非 `ConnectionHolder`**：`TxModeAspect` 位于事务拦截器**外层**（`LOWEST_PRECEDENCE - 1`，入口处事务尚未开启，无法写入 Holder）→ 无条件记录模式、帧结束 save/restore（内层不覆盖）；消费点（写入校验/提交）以"是否存在活动事务"决定生效与否——未开事务时模式无人消费，天然满足"不生效也不报错"
- **XA/THREE_PC 校验放在提交时**：切面无法区分"有无事务"（见上），改为 `getCommitStrategy` 查不到即报"尚未实现"——提交失败 → 事务回滚，语义仍是"调用期报错回滚"
- **写入校验在语句代理内、计入写名单之前**：`executeUpdate` 时先校验后计数——第二分片的语句在校验失败时**根本不执行**，回滚天然干净
- **传播判定不依赖 Spring 内部**：`REQUIRES_NEW` = `DefaultTransactionStatus.getSuspendedResources() != null`（首次开启的 REQUIRES_NEW 无外层可挂起，与 REQUIRED 等价不拦——无协调风险，见已知限制）；`NESTED` = `status.hasSavepoint()`；外层已跨库的判定用自维护 ThreadLocal 标记（写名单 ≥2 时置位、对应连接 close 时清理，与连接生命周期对称）
- **执行期异常经 MyBatis 包装**（`MyBatisSystemException → PersistenceException → ShardingException`）——与 Spring 数据访问异常惯例一致，集成测试沿 cause 链断言根因；切面期异常（漏标 @TxMode 的入口场景除外）原样抛出
- **savepoint 归属连接跟踪**：`setSavepoint` 记录归属连接，`rollback/releaseSavepoint` 按归属连接执行（JDBC savepoint 本就是单连接语义）——跨分片校验拒绝后，事务层回滚 savepoint 不再被"多连接"守卫反噬，NESTED 禁止异常得以原样浮出

### 已知限制（记录，非缺陷）

- 首次开启的 `REQUIRES_NEW`（无外层事务）不受传播校验——功能上与 `REQUIRED` 等价，无独立提交冲突风险
- 传播判定依赖 `TransactionAspectSupport` 的注解驱动状态：`TransactionTemplate` 编程式事务中检测不到 REQUIRES_NEW（usage.md 约定 `@Transactional` 风格，不覆盖）
- `XA`/`THREE_PC` 枚举可用、提交时报"尚未实现"，等待后续 2PC/3PC 阶段实现策略
- 跨分片提交部分失败 = LOCAL 固有语义（无原子性）：已交分片保留，明细日志/异常信息供对账

### 问题与修复

| # | 问题 | 原因 | 修复方式 | 状态 |
|---|---|---|---|---|
| 1 | 加了 `@TxMode` 仍报"未标注"，跨分片用例全挂 | 切面在事务拦截器外层，入口处 `isActualTransactionActive()` 为 false，条件把模式设置跳过了 | 模式无条件记录（帧 save/restore），生效与否交给消费点判断 | 已修复 |
| 2 | 跨分片用例抛 `MyBatisSystemException` 而非 `ShardingException` | 执行期异常经 `DefaultSqlSession` 的 `ExceptionFactory` 包装 | 测试改沿 cause 链断言根因（框架行为与 Spring 惯例一致，不改产品代码） | 已解决 |
| 3 | NESTED 用例抛出 `TransactionSystemException: Could not roll back to JDBC savepoint`，真因被掩盖 | 写第二分片时 ds_1 已在 prepare 阶段取出 → `rollback(savepoint)` 的 size>1 守卫先炸，覆盖了 NESTED 禁止异常 | savepoint 归属连接跟踪：回滚按创建时连接执行（JDBC 正确语义），守卫只留在创建时 | 已修复 |

---

## 阶段 6：2PC（`Mode.XA`）

**日期：** 2026-11-08（当日完成）
**产出：** 140 用例全绿（前五阶段回归 + 新增 11：协调者 8、恢复决策 6 中的新增、XA 集成 3——含崩溃恢复全链路）

### 实现决策

- **真 XA（`XAResource`）而非本地两阶段模拟**：本地模拟下参与者是普通未提交事务，进程死亡随会话回滚，PREPARED 承诺无法兑现；真 XA 的 prepared 分支在 InnoDB 以 in-doubt 持久存活（断连/崩溃均不回滚），恢复才有现场——这是"强一致"承诺的根基
- **池统一换 `DruidXADataSource`**（1.2.7，继承 `DruidDataSource`，`DataSourceFactory` 只换实例化一行）：同一池服务 LOCAL（普通 `getConnection`）与 XA（`getXAConnection`）两种模式，避免"用了 XA 才发现池不支持"
- **分支开启时机**：`ShardingConnection.physicalFor` 在 `mode==XA 且活动事务` 时取 XA 连接 → 回填 → `XA START`（先于该节点任何 SQL）；未开事务时模式不生效、保持普通连接（与 `@TxMode` 不生效语义一致）
- **Xid 确定性**：`formatId=0x53484152("SHAR")`，gtrid=UUID hex，**bqual=物理节点 key**——恢复时由日志的 globalId+参与者清单直接重建 Xid 匹配名册，无需额外映射表
- **日志表由框架新建 + 使用时校验**（用户要求）：`@TxMode(XA)` 入口（切面）即 `CREATE TABLE IF NOT EXISTS sharding_tx_log ... utf8mb4`，失败附完整 DDL 报错；"表已预建但无 CREATE 权限"探测放行；恢复扫描遇 42S02 静默跳过——未用 XA 的应用零负担
- **XA 无单分片跳日志快速路径**：分支一旦开启必须走完整 end/prepare/commit 生命周期，统一落日志保证恢复语义一致（与 LOCAL 的快速路径不同）
- **循环依赖破解**：`数据源装配收集策略 → 策略依赖日志 → 日志依赖数据源`——`TransactionLog` 池以 `Supplier` 惰性提供（`ObjectProvider` 注入），启动期无环
- **恢复裁决失败不覆盖终态**：参与者缺失/裁决异常 → 日志保留 PREPARED/BEGIN + detail 记录，下次启动重试；名册中无该分支 → 视为已处理幂等推进

### 过程问题（真 XA 落地踩的坑，全部实测解决）

| # | 问题 | 原因 | 修复方式 | 状态 |
|---|---|---|---|---|
| 1 | 手工装配测试 XA 分支从未开启，写入变普通未提交事务、行锁拖死后续用例 | `isXaBranchNeeded()` 要求活动事务，手工装配无 Spring 拦截器置位 | 测试显式 `setActualTransactionActive(true)`（与 doBegin 等价）；测试基建补 in-doubt 清理与会话强杀 | 已修复 |
| 2 | 日志写中文明细报 `Incorrect string value` | 历史表继承库默认 latin1 | DDL 显式 `utf8mb4` + 首次 ensure 时 `ALTER CONVERT` 兼容旧表 | 已修复 |
| 3 | 恢复报"完成"但分支仍 in-doubt、数据没提交 | **Connector/J `XAResource.recover(TMNOFLAGS)` 返回空列表**（探针实测），扫描标志才返回 | `recover(TMSTARTRSCAN\|TMENDRSCAN)`；临时探针验证后删除 | 已修复 |
| 4 | `XA RECOVER` 结果集无 `gtrid/bqual` 列 | MySQL 5.7 列为 `formatID/gtrid_length/bqual_length/data`（data=原始拼接） | 按长度切片 data 还原 gtrid/bqual | 已修复 |
| 5 | 同一 Statement 上 ResultSet 未关就执行 `XA ROLLBACK` 报错 | JDBC 规范限制 | 先收集回滚语句、关结果集后再执行 | 已修复 |
| 6 | 崩溃模拟后 tearDown 遗留锁等待 50s 级联失败 | 上轮 in-doubt 未清理 + `pool.close()` 不杀借出中的会话 | setUp 按 formatID 清理遗留 in-doubt；崩溃模拟显式 `KILL` 分支会话（等价进程死亡） | 已修复 |

### 验证语义（集成③实测确认）

prepared 分支在**分支会话被 KILL 后**仍出现在 `XA RECOVER` 名册（InnoDB 持久化 in-doubt）→ 全新连接按 Xid 补 `XA COMMIT` → 数据落库、名册清空——"协调者死了现场还在、重启按日志兑现"全链路成立。

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
