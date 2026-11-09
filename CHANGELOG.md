# Changelog

本项目遵循 [Semantic Versioning](https://semver.org/)。

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
