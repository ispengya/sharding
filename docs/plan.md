# 改造计划

三块内容：产品形态 → 事务 → 性能优化。

## 一、产品形态（先保证能用）

目标：分库分表 + 读写分离 + 可自定义分片算法。**顺序：先分片、后读写分离**（读写分离挂载在分片产出的逻辑数据源上，分片阶段先留好"逻辑数据源 → 物理连接"的选取口子）。

- [ ] 分库分表（已有）：注解路由 + Druid SQL 改写，保持现状；配置重构为按逻辑表的 `rules`（不配置 = 不分片，落 primary）
- [ ] 读写分离：数据源配置 slaves 即开启；写永远走主，读默认走主，`route = SLAVE` 显式走从库
- [ ] 一写多读：多个从库间负载均衡（轮询），主库不可用时的降级规则
- [ ] 注解设计（已定稿）：`@DBRouter(route = MASTER | SLAVE)`，默认 MASTER；仅 Mapper 类/方法可用，方法级覆盖类级；SLAVE + 事务内 → 校验报错
- [ ] 自定义分片键算法：策略接口已有（`DBShardingStrategy`），补齐分表策略的自定义配置与加载方式
- [ ] README 使用文档更新

## 二、事务（一个线程、一个事务内操作多个数据源）

目标：单数据源事务保持 Spring 原生强一致（无需标注）；跨数据源写的事务用 `@TxMode` 注解指定协调模式，**无全局配置**；跨数据源写未标注 → 校验报错。

**核心改造：路由模型从 `AbstractRoutingDataSource`（事务锁死单分片）换成包装式多连接**
（逻辑连接对 Spring 是一个，内部按 SQL 路由懒加载各分片物理连接）。

- [ ] 包装式多连接：`ShardingConnection` 路由取连接、事务设置回填、enlist 登记、关闭归还
- [ ] 单分片事务走快速路径（原生提交，零开销）；多分片进入协调策略
- [ ] 协调策略 SPI（`prepare` / `commit` / `rollback`）：
  - [ ] **LOCAL**：循环逐个提交，失败重试 + 记录已提交/未提交明细（默认模式）
  - [ ] **2PC**：prepare 全部通过 → 统一 commit/rollback；协调者 + 事务日志持久化 + 启动恢复
  - [ ] **3PC**：canCommit → preCommit → commit，各阶段超时机制（参与者超时默认 abort）
- [ ] `@TxMode` 注解：`Mode.LOCAL / XA / THREE_PC`，方法级 > 类级，无全局兜底
- [ ] 校验：事务写入 ≥ 2 个数据源未标注 `@TxMode` → 调用时报错（回滚）；单数据源写、跨数据源只读免标注
- [ ] 集成测试：单分片回滚、跨分片部分失败、协调者宕机恢复、超时路径

## 三、性能优化分析（后期）

前两块稳定后再做，先记录方向：

- 基准测试：三种事务模式的 TPS / 延迟对比，LOCAL vs 2PC 代价量化
- 路由开销：分片键提取与 SQL 解析（Druid）的缓存优化
- 连接池：多连接 enlist 后的占用与等待分析
- 读写分离：读负载均衡策略效果、主从延迟监控

## 附：完整配置示例（定稿）

```yaml
sharding:
  # 未配置分片规则的逻辑表一律落 primary，不分库分表
  primary: ds_1

  dataSources:                       # 逻辑数据源：资源 + 读写分离开关
    ds_1:                            # 配了 slaves 即开启读写分离，不配则读写同库
      master:                        # 每个物理节点的连接池配置独立，不写用内置默认
        url: jdbc:mysql://localhost:3306/db_1
        username: root
        password: root
        driverClassName: com.mysql.cj.jdbc.Driver
        connection:
          initialSize: 5
          minIdle: 5
          maxActive: 20
          maxWait: 60000
      slaves:
        - url: jdbc:mysql://slave1:3306/db_1
          username: root
          password: root
          connection:                # 从库读流量大，池可独立调大
            maxActive: 50
        - url: jdbc:mysql://slave2:3306/db_1
          username: root
          password: root
      loadBalance: round_robin       # slaves 间负载均衡：round_robin | random

    ds_2:                            # 不配 slaves → 读写同一个库
      master:
        url: jdbc:mysql://localhost:3306/db_2
        username: root
        password: root

  rules:                             # 可选：按逻辑表显式配置，无默认规则
    order:
      db:
        databases: [ds_1, ds_2]      # 分片范围（dbCount 派生自列表长度）
        shardingKey: userId          # 分库键
        algorithm: hash              # 内置算法，或自定义算法的 @Algorithm 名
      table:
        shardingKey: orderId         # 分表键（可与分库键不同）
        algorithm: hash
        tableCount: 8
    payment:
      db:
        databases: [ds_1, ds_2]
        shardingKey: tradeDate       # 范围类算法配合参数使用
        algorithm: range_month
        algorithmParams:
          "2026-01~2026-06": ds_1
          "2026-07~2026-12": ds_2
      table:
        shardingKey: tradeDate
        algorithm: range_day
        tableCount: 30
```

路由链路与关键规则：

```
写 SQL → 分片（rules，未配置落 primary）→ 组内取 master
读 SQL → 分片（rules，未配置落 primary）→ 组内按 loadBalance 取 slave（无 slaves 则取 master）
事务内读 → 一律 master（从库延迟会破坏一致性）
```
