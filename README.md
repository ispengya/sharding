# my-sharding-service

基于 Spring Boot + MyBatis 的分库分表中间件：分片路由、读写分离、跨数据源事务（LOCAL / 2PC / 3PC）一体，配置驱动、注解即用。

> **状态：开发中，尚未发布。** 完整使用说明见 **[docs/usage.md](docs/usage.md)**（以该文档为验收口径）；设计与实现过程见 [docs/plan.md](docs/plan.md) / [docs/steps.md](docs/steps.md) / [docs/journal.md](docs/journal.md)。

## 特性

- **分库分表**：按逻辑表配置规则（`rules`），无规则的表落 primary 直通；SQL 自动改写为物理表名（基于 Druid 解析，支持 JOIN）
- **读写分离**：`slaves` 配置即开启，`@DBRouter(route = SLAVE)` 显式走从库，组内 `round_robin` / `random` 负载均衡
- **自定义分片算法**：实现 `ShardingAlgorithm` 并标注 `@Algorithm("名字")`，配置按名引用
- **跨数据源事务**：逻辑连接 1:N 分发 + 三种协调模式——`Mode.LOCAL`（循环提交+重试+明细）、`Mode.XA`（真两阶段，事务日志 + 启动恢复）、`Mode.THREE_PC`（三阶段 + 阶段超时）
- **使用规则显式校验**：跨分片写必须标 `@TxMode`、禁用 `REQUIRES_NEW`/`NESTED`、事务内禁 `route = SLAVE`——违规调用即报错，不静默
- **启动期失败快**：数据源/算法/负载均衡引用、`@DBRouter` 标注位置等错误在启动时抛出

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>com.ispengya</groupId>
    <artifactId>my-sharding-service</artifactId>
    <version>0.0.1</version>
</dependency>
```

### 2. 配置

```yaml
sharding:
  primary: ds_1                     # 未配置分片规则的逻辑表一律落 primary

  dataSources:                      # 逻辑数据源组
    ds_1:                           # 配了 slaves 即开启读写分离
      master:
        url: jdbc:mysql://localhost:3306/db_1
        username: root
        password: root
        driverClassName: com.mysql.cj.jdbc.Driver
        connection:                 # 节点级连接池参数，不写用内置默认
          initialSize: 5
          maxActive: 20
      slaves:                       # 每个从库连接池独立配置
        - url: jdbc:mysql://localhost:3307/db_1
          username: root
          password: root
      loadBalance: round_robin      # 从库间均衡：round_robin | random
    ds_2:                           # 不配 slaves → 读写同一个库
      master:
        url: jdbc:mysql://localhost:3306/db_2
        username: root
        password: root

  rules:                            # 可选：按逻辑表配置；不配置 = 不分片
    order:
      db:
        databases: [ds_1, ds_2]     # 分片范围（即 dataSources 的 key）
        shardingKey: userId         # 分库键
        algorithm: hash             # 内置 hash / range_month / range_day，或自定义算法名
      table:
        shardingKey: orderId        # 分表键（可与分库键不同）
        algorithm: hash
        tableCount: 8
```

### 3. 代码

```java
@Mapper
public interface OrderMapper {

    // 裸注解：按 rules 中的 shardingKey 自动取键值
    @DBRouter
    @Select("SELECT * FROM order WHERE id = #{id}")
    Order getById(@Param("id") Long id);

    // 参数多时显式指定分库键；route = SLAVE 显式读从库
    @DBRouter(key = "userId", route = Route.SLAVE)
    @Select("SELECT count(*) FROM order WHERE user_id = #{userId}")
    long countByUser(@Param("userId") Long userId);
}
```

```java
@Service
public class OrderService {

    @Transactional                     // 单数据源写：原生强一致，无需标注
    public void create(Order order) { ... }

    @TxMode(Mode.LOCAL)                // 跨分片写：必须标注协调模式
    @Transactional
    public void placeOrder(Order a, Order b) { ... }

    @TxMode(Mode.XA)                   // 资金类强一致：2PC + 事务日志 + 启动恢复
    @Transactional
    public void settle(Trade trade) { ... }
}
```

## 自定义分片算法

```java
@Algorithm("rangeMonth")              // value 即配置里的 algorithm 引用名
public class RangeMonthShardingAlgorithm implements ShardingAlgorithm {
    @Override
    public String calculateDb(ShardingContext ctx) {
        return ...;                   // 返回 dataSources 中的 key
    }

    @Override
    public String calculateTable(ShardingContext ctx) {
        return ctx.getLogicTableName() + "_" + ...;   // 返回物理表名
    }
}
```

```yaml
sharding:
  rules:
    payment:
      db:
        databases: [ds_1, ds_2]
        shardingKey: tradeDate
        algorithm: rangeMonth         # 按 @Algorithm 的 value 引用
```

类需位于应用组件扫描范围内；内置算法：`hash`（两侧可用）、`range_month`（仅分库）、`range_day`（仅分表）。

## 跨数据源事务规则速查

| 场景 | 要求 |
|---|---|
| 单数据源写 | 无需 `@TxMode`，原生事务 |
| 跨数据源写 | 入口方法必须 `@TxMode(LOCAL/XA/THREE_PC)`，否则调用时报错回滚 |
| 跨数据源只读 | 无需标注 |
| 传播行为 | 跨数据源事务仅支持 `REQUIRED`（`REQUIRES_NEW`/`NESTED` 报错） |
| `route = SLAVE` | 活动事务内使用报错；该组无 slaves 时降级走主库 |
| XA / 3PC 事务日志 | 表 `sharding_tx_log` 由框架在 primary 自动创建；启动时自动恢复悬决事务 |

模式选择：一般业务 `LOCAL`（无原子性，失败靠明细日志对账）；要求强一致用 `XA`（推荐）或 `THREE_PC`。

## 构建与测试

- 构建 JDK：**8**（`scripts/test.sh` 会自动切换本机 JDK）
- 单测 + 集成测试（需 Docker，一键起真实 MySQL 三节点：2 主分库 + 1 从读写分离）：

```shell
bash scripts/test.sh          # 起环境 → 等健康 → mvn test
mvn test                      # 无 Docker 时只跑单测需排除集成：
mvn test -Dtest='!*IntegrationTest'
```

当前测试规模：158 用例（单测 + 真实 MySQL 集成），覆盖分片路由、读写分离、事务三模式与崩溃恢复。

## 注意事项

- **分片键**：选择分布均匀、查询常带的字段；`hash` 掩码取模建议分片数为 2 的幂次方
- **跨库 JOIN / 跨库查询**：不支持，设计上避免
- **读己之写**：从库有复制延迟，写后立读请用默认主库或 `route = Route.MASTER`
- **连接池容量**：跨分片事务会同时持有多个池的连接，`maxActive` 按并发跨分片事务数规划
- **XA 参与者**：仅主库（事务内禁止路由到从库）；存储引擎需 InnoDB

## 版本

- `0.0.1`（开发中，未发布）——配置模型、包结构与旧版（`dbRule`/`strategyClass`）完全不兼容，详见 [docs/journal.md](docs/journal.md)
