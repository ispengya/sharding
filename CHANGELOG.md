# Changelog

本项目遵循 [Semantic Versioning](https://semver.org/)。

## [0.2.0] - 2026-11-11

### 破坏性变更（依赖坐标）

- **多模块化**：单 artifact `io.github.ispengya:sharding` 拆分为三个坐标，使用方按自身
  Spring Boot 版本选择 starter（core 由 starter 传递引入，无需单独声明）：
  - `sharding-core` —— 零 Spring Boot 依赖的核心（Java 8 字节码基线）
  - `sharding-boot2-starter` —— Spring Boot 2.x 应用（Java 8+，Boot 2.1 起兼容）
  - `sharding-boot3-starter` —— Spring Boot 3.x 应用（Java 17+，jakarta 线）
- 配置键、注解与 API 与 0.1.x 完全一致，仅 Maven 坐标变化

### 新增

- **Spring Boot 3 支持**：`AutoConfiguration.imports` 装配 + MyBatis starter 3.x +
  `com.mysql:mysql-connector-j`；core 的 Spring 编译面锁定 5.1 API（provided，
  运行时兼容 Spring 5.1 ~ 6.x，XA/JDBC 均为 JDK 内置 API，无 jakarta 迁移负担）
- **双 JDK 测试矩阵**：`scripts/test.sh` 一键跑 JDK 8（core + boot2，严格 8 全链路）
  与 JDK 17（全 reactor 含 Boot 3 集成）两腿；测试环境收编项目内 `docker/`
  （容器 `sharding-test-*`，即起即新，结束自动 `down -v`）
- 脚本环境自适应：Git Bash / WSL（经 `cmd.exe` 调 Windows Maven）/ 原生 Linux 均可执行

### 修复

- **事务日志明细丢失（3PC 阶段超时路径）**：被中断的 stage 线程与主线程兜底回滚并发各写一次
  `ROLLED_BACK` 时，收尾写（`detail=null`）会覆盖失败写的诊断明细（"prepare 失败节点=…"）——
  排障关键时刻线索被抹掉。修复：`detail` 为空视为"无新信息"不覆盖已有明细（SQL 层
  `COALESCE(NULLIF(?, ''), detail)`），已有异常明细在终态后保留作为审计轨迹；
  新增集成用例固化该语义（双写竞态后明细保留）
- 测试依赖升级 Mockito 4.11（唯一同时兼容 JDK 8 与 17 的主线），并修正 3PC 阶段超时
  用例对"中断的 stage 线程与主线程兜底回滚并发写日志"的计数敏感断言（改为状态语义断言）

## [0.1.1] - 2026-11-11

### 修复

- **分片键提取**：单标量参数（数字 / 字符串 / 日期时间，不加 `@Param`）即键值本身——
  对齐 MyBatis 单简单参数"任意占位符名绑定整值"的语义，`getById(Long userId)` 裸参数也能路由；
  枚举与数组刻意不按标量处理（identity hashCode 跨 JVM 不稳定，宁可报错）
- **分片键提取**：多参数方法中的 Map 参数参与兜底提取（此前被跳过——
  `query(@Param("cond") Map cond, ...)` 里 cond 携带分片键会报"无法提取分库键"）
- **hash 算法**：键值为集合 / 数组 / Map 时直接报错，不再按整体 hashCode 静默路由到错误分片

## [0.1.0] - 2026-11-09

首个公开版本。

### 功能

- **分库分表**：按逻辑表配置 `rules`（`db` / `table` 段），Druid SQL 解析自动改写物理表名；
  未配置规则的表落 `primary` 直通；支持 JOIN（同一分片内）
- **语句级路由**：按"这条 SQL + 这份参数"现场计算分库落点，未标注解也正确路由；
  同一 SQL 多张分片表路由必须一致，缺分库键/解析失败一律报错
- **方法基线**：`@DBRouter`（仅限 Mapper 接口/方法）提供方法级落点、入口 fail-fast 与读写角色
- **读写分离**：`slaves` 配置即开启，`route = SLAVE` 读从库，组内 `round_robin` / `random` 负载均衡；
  事务内 `SLAVE` 直接报错
- **自定义算法**：实现 `ShardingAlgorithm` + `@Algorithm("名字")`，配置按名引用；内置 `hash` / `range_month` / `range_day`
- **跨数据源事务**：逻辑连接 1:N 分发，三种协调模式——
  `LOCAL`（循环提交 + 重试 + 明细）、`XA`（真两阶段 + 事务日志 + 启动恢复）、
  `THREE_PC`（三阶段 + 阶段超时）
- **违规即报错**：跨分片写必须标 `@TxMode`；禁用 `REQUIRES_NEW` / `NESTED`；`@DBRouter` 嵌套调用直接报错
- **启动期校验**：数据源 / 算法 / 负载均衡引用、`@DBRouter` 标注位置等错误启动即失败
- **性能与一致性设施**：JMH 微基准与端到端压测（含行数与事务日志一致性校验），见 `docs/perf.md`

### 破坏性变更

- 配置键全面替换：`dataSources`（节点级连接池）+ `rules`（按逻辑表），旧 `dbRule` / `tableRule` 不再支持
- 包结构按职责重排（`annotation` / `algorithm` / `rule` / `route` / `sql` / `datasource` / `config` / `tx` 等）
- 策略注册改为注解扫描（`@Algorithm` / `@LoadBalance`），移除 `strategyKey` / `strategyClassName`
- `@DBRouter` 仅允许标注在 Mapper 接口的类或方法上，且移除 `key` 属性（键名只由配置决定）
- 路由上下文不再保存/恢复外层帧：`@DBRouter` 方法嵌套调用直接抛错
