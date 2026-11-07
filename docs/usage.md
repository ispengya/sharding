# 使用文档

> 本文档是目标使用形态的设计稿，对应 [plan.md](plan.md) 的改造内容，尚未全部实现。以本文档作为实现验收的口径。

## 1. 完整配置

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

  rules:                             # 可选：按逻辑表显式配置，不配置 = 不分片
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

配置要点：

- **不配置 `rules` 的逻辑表不分片**，落 `primary` 数据源，表名原样，不需要分片键
- **不配置 `slaves` 就没有读写分离**，该数据源读写同一个库
- **`connection` 属于物理节点**，master 和每个 slave 各自一份，缺省用内置默认值
- `rules.*.db.databases` 与 `primary` 引用的都是 `dataSources` 的 key

## 2. 分片路由：`@DBRouter`

`@DBRouter` 只能标注在 **Mapper 接口的类或方法** 上，标到其他 Bean 上启动即报错。

```java
@Mapper
@DBRouter                          // 类级：全类方法默认生效
public interface OrderMapper {

    // 裸注解：按 rules 中配置的 shardingKey 自动取键值
    @DBRouter
    @Select("SELECT * FROM order WHERE id = #{id}")
    Order getById(@Param("id") Long id);

    // 参数多时用 key 显式指定从哪个参数取分库键值
    @DBRouter(key = "userId")
    @Insert("INSERT INTO order(id, user_id) VALUES(#{id}, #{userId})")
    int insert(Order order);
}
```

属性优先级：**方法级 > 类级 > 全局默认**。

分库键取值规则（取不到直接报错，不静默）：

1. 配了 `key`：按参数名/`@Param` 名精确匹配
2. 没配 `key`：按 `rules` 里的 `shardingKey` 匹配参数；单参数直接取值
3. 匹配失败 → 抛 `ShardingException`（不做 null 兜底）

> 为什么必须有注解：分库要在取连接之前完成，MyBatis 拦截器执行时连接已取走，只能靠切面在进入 Mapper 方法时取键值。`@DBRouter` 是使用面唯一的必学项。

## 3. 读写分离：`route` 属性

**写永远走主库。读默认走主库，`route = Route.SLAVE` 显式走从库。**
（`Route` 枚举位于 `com.ispengya.sharding.annotation.Route`，可静态导入 `SLAVE`/`MASTER` 简写。）

```java
@DBRouter                                  // 默认 MASTER：读写都走主库
@Select("SELECT * FROM user WHERE id = #{id}")
User getById(@Param("id") Long id);

@DBRouter(route = Route.SLAVE)             // 显式从库：多从库间按 loadBalance 均衡
@Select("SELECT count(*) FROM order WHERE user_id = #{userId}")
long countByUser(@Param("userId") Long userId);

@DBRouter(route = Route.MASTER)            // 显式主库（用于覆盖类级 SLAVE）
@Select("SELECT * FROM order WHERE id = #{id}")
Order getAfterWrite(@Param("id") Long id);
```

规则：

| 写法 | 读 | 写 | 事务内 |
|---|---|---|---|
| `@DBRouter` | 主库 | 主库 | ✅ |
| `route = MASTER` | 主库 | 主库 | ✅ |
| `route = SLAVE` | 从库 | —— | ❌ 校验报错 |

- 该数据源没配 `slaves` 时，`SLAVE` 无从库可用 → 按主库处理
- `route = SLAVE` 在活动事务内调用 → 抛 `ShardingException`（从库延迟会破坏事务一致性，属于使用错误，直接失败）

## 4. 事务：`@Transactional` + `@TxMode`

事务还是 Spring 的 `@Transactional`。**没有全局事务模式配置：跨数据源写的事务必须在入口方法上用 `@TxMode` 指定模式，单数据源写不需要标注。**

```java
@Service
public class OrderService {

    @Transactional                      // 单数据源写（含未分片表落 primary）→ 无需标注，原生强一致
    public void createOrder(Order order) { ... }

    @TxMode(Mode.LOCAL)                 // 跨数据源写 → 必须标注模式
    @Transactional
    public void createUserAndOrder(User u, Order o) { ... }

    @TxMode(Mode.XA)                    // 资金场景单独用 2PC，不影响其他方法
    @Transactional
    public void settle(Trade trade) { ... }
}
```

| 模式 | 行为 | 适用 |
|---|---|---|
| `Mode.LOCAL` | 各分片连接循环提交，失败重试并记录已提交/未提交明细 | 一般业务 |
| `Mode.XA` | prepare 全部通过才统一 commit，协调者 + 事务日志 + 启动恢复（2PC） | 资金类强一致 |
| `Mode.THREE_PC` | 2PC 基础上增加 canCommit/preCommit 与各阶段超时 | 同上，降低阻塞 |

优先级：**方法级 > 类级**（可标在 Service 类上整类生效；无全局兜底）。

规则：

- **单数据源写** → 不需要 `@TxMode`，绝大多数业务属于此类
- **事务内写入 ≥ 2 个数据源但未标注 `@TxMode`** → 调用时校验报错，事务整体回滚（此时尚未提交，回滚安全）
- **跨数据源只读**事务 → 不需要标注（没有需要协调的提交）
- `@TxMode` 不开事务、只定模式；未处于事务中时标注不生效也不报错
- 模式在**开启事务的入口方法**上生效，事务已打开后，内层方法再标 `@TxMode` 不改变模式
- 跨数据源事务仅支持传播行为 `REQUIRED`（禁用 `REQUIRES_NEW` / `NESTED`）
- 事务内不做 RPC / 发消息；事务内读自动落主库（默认 MASTER 已保证）

## 5. 自定义分片算法

实现接口并标注 `@Algorithm`，配置按注解名引用（无需注册 Bean，也不依赖 Bean 名）：

```java
@Algorithm("rangeMonth")               // 扫描发现并实例化，value 即配置引用名
public class RangeMonthShardingAlgorithm implements ShardingAlgorithm {
    @Override
    public String calculateDb(ShardingContext ctx) {
        // ctx：键值、databases 范围、algorithmParams
        return ...;                    // 返回 dataSources 中的 key
    }

    @Override
    public String calculateTable(ShardingContext ctx) {
        throw new ShardingException("本算法仅用于分库");   // 只支持一侧时显式拒绝
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
        algorithm: rangeMonth          # 引用 @Algorithm("rangeMonth") 的算法名
```

规则：

- 分库与分表是**两个方法**：`calculateDb`（返回数据源名）与 `calculateTable`（返回物理表名），按规则段各走各的
- 类必须位于应用组件扫描范围内（`@Algorithm` 是 Spring 注解，走正常组件扫描发现）；未标注注解、名称为空或重名 → 启动报错
- `@Algorithm(scope = ...)` 声明算法允许的规则段（`DB` / `TABLE` / `BOTH`，默认 `BOTH`），错配启动即报错
- 算法类可正常使用 `@Value` / `@Autowired`（实例由 Spring 创建）

内置算法：`hash`（取模，两侧可用）、`range_month`（仅分库）、`range_day`（仅分表）。

## 6. 典型场景

**① 单库单表（最简）**

```yaml
sharding:
  primary: ds_1
  dataSources:
    ds_1:
      master: {url: ..., username: ..., password: ...}
```

代码：不需要任何注解，正常写 `@Transactional` 即可。

**② 只开读写分离，不分片**

```yaml
dataSources:
  ds_1:
    master: {url: ...}
    slaves: [{url: ...}]
```

```java
@DBRouter(route = Route.SLAVE)   // 想摊给从库的读加上这一个属性，其余不写
```

**③ 分库分表，无主从**

```yaml
rules:
  order:
    db: {databases: [ds_1, ds_2], shardingKey: userId, algorithm: hash}
    table: {shardingKey: orderId, algorithm: hash, tableCount: 8}
```

```java
@Mapper
@DBRouter                  // 类上标一次，全类按规则分片
public interface OrderMapper { ... }
```

**④ 分片 + 主从混合**

`rules` 管去哪个数据源组，`slaves` 管组内用谁，互不干扰；组内主从按第 3 节规则选。

**⑤ 跨分片事务**

```java
@TxMode(Mode.LOCAL)               // 跨数据源写，必须标注模式；强一致场景换 Mode.XA
@Transactional
public void placeOrder(User u, Order o, Stock s) { ... }
```

只碰一个数据源的事务永远不需要 `@TxMode`；漏标时跨数据源写会在调用时校验报错。

## 7. 校验报错清单

以下情况**启动或调用时直接抛 `ShardingException`**，不静默兜底：

| 场景 | 时机 |
|---|---|
| `@DBRouter` 标注在非 Mapper Bean 上 | 启动 |
| `databases` / `primary` 引用了不存在的数据源 key | 启动 |
| `algorithm` 引用的算法不存在 | 启动 |
| 算法类未标注 `@Algorithm` / 名称为空 / 重名 | 启动 |
| 算法 `scope` 与规则段错配（如仅分库算法配到 table 段） | 启动 |
| `loadBalance` 引用的策略不存在 / 未标注 `@LoadBalance` / 重名 | 启动 |
| 分库键取不到值（参数里找不到） | 调用 |
| `route = SLAVE` 在活动事务内使用 | 调用 |
| 事务写入多个数据源但未标注 `@TxMode` | 调用（事务整体回滚） |
| 跨分片事务使用 `REQUIRES_NEW` / `NESTED` | 调用 |
