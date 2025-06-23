# My-Sharding-Service

一个轻量级的分库分表中间件，基于Spring Boot + MyBatis实现，支持自定义分片策略，使用简单，易于扩展。

## 特性

- 支持分库分表
- 支持自定义分片策略
- 支持注解和配置方式
- 基于Druid SQL解析
- 支持动态数据源切换
- 支持复杂SQL（JOIN、子查询等）

## 快速开始
### 1. 克隆项目
```shell
git clone https://github.com/ispengya/sharding.git
```

### 2. 本地打包
```shell
mvn clean install
```

### 3. 引入依赖

```xml
<dependency>
    <groupId>com.ispengya</groupId>
    <artifactId>my-sharding-service</artifactId>
    <version>0.0.1</version>
</dependency>
```

### 4. 配置文件

```yaml
sharding:
  # 主数据源配置
  primary: ds_1
  
  # 数据源配置
  dataSources:
    ds_1:
      url: jdbc:mysql://localhost:3306/db_1
      username: root
      password: root
      driverClassName: com.mysql.cj.jdbc.Driver
      # 连接池配置
      connection:
        initialSize: 5        # 初始连接数
        minIdle: 5           # 最小空闲连接
        maxActive: 20        # 最大活动连接
        maxWait: 60000       # 获取连接等待超时时间
        timeBetweenEvictionRunsMillis: 60000  # 空闲连接检查时间
        minEvictableIdleTimeMillis: 300000    # 连接最小生存时间
        validationQuery: SELECT 1             # 连接检查SQL
        testWhileIdle: true                  # 空闲时检查连接
        testOnBorrow: false                  # 获取连接时检查
        testOnReturn: false                  # 归还连接时检查
    
    ds_2:
      url: jdbc:mysql://localhost:3306/db_2
      # ... 其他配置同上
  
  # 分库规则配置
  dbRule:
    shardingKey: userId      # 分片键
    dbCount: 2              # 数据库数量
    strategyKey: hash       # 分片策略标识
    strategyClass: com.ispengya.sharding.strategy.HashShardingStrategy  # 策略实现类
  
  # 分表规则配置
  tableRules:
    order:                  # 逻辑表名
      shardingKey: orderId  # 分片键
      tableCount: 4         # 每个库的表数量
      strategyKey: hash     # 分片策略标识
      strategyClass: com.ispengya.sharding.strategy.HashShardingStrategy  # 策略实现类
    user:                   # 另一个表的分片规则
      shardingKey: userId
      tableCount: 2
      strategyKey: hash
      strategyClass: com.ispengya.sharding.strategy.HashShardingStrategy
```

### 5. 使用方式

#### 5.1 注解方式

```java
@Mapper
public interface UserMapper {
    @DBRouter    // 添加分库注解
    @Insert("INSERT INTO user (id, username) VALUES (#{id}, #{username})")
    int insert(User user);
    
    @DBRouter
    @Select("SELECT * FROM user WHERE id = #{id}")
    User selectById(@Param("id") Integer id);
    
    @DBRouter
    @Select("SELECT o.user_id FROM user u LEFT JOIN order o on u.username = o.username where u.username = #{username}")
    Integer getUserIdByUsername(@Param("username") String username);
}
```

## 分片策略

### 1. 默认分片策略

系统默认提供了两种分片策略：

1. **Hash分片策略**（默认）
```java
// 默认的hash分片实现
public class HashShardingStrategy implements DBShardingStrategy {
    @Override
    public String shard(DBShardingRule rule, String shardingValue) {
        int size = rule.getDbCount() * rule.getTableCount();
        int idx = (size - 1) & (shardingValue.hashCode() ^ (shardingValue.hashCode() >>> 16));
        int dbIdx = idx / rule.getTableCount() + 1;
        return "ds_" + dbIdx;
    }
}
```

### 2. 自定义分片策略

1. **实现分片策略接口**
```java
public class CustomShardingStrategy implements DBShardingStrategy {
    @Override
    public String shard(DBShardingRule rule, String shardingValue) {
        // 自定义分片逻辑
        return "ds_" + calculateDbIndex(shardingValue);
    }
}
```

2. **配置文件中指定策略**
```yaml
sharding:
  dbRule:
    shardingKey: userId
    dbCount: 2
    strategyKey: custom    # 策略标识
    strategyClass: com.example.CustomShardingStrategy  # 策略全限定类名
```

## 配置参数说明

### 1. 数据源配置

| 参数 | 说明 | 必填 | 默认值 |
|------|------|------|--------|
| url | 数据库连接URL | 是 | - |
| username | 数据库用户名 | 是 | - |
| password | 数据库密码 | 是 | - |
| driverClassName | 数据库驱动类 | 否 | com.mysql.cj.jdbc.Driver |

### 2. 连接池配置

| 参数 | 说明 | 必填 | 默认值 |
|------|------|------|--------|
| initialSize | 初始连接数 | 否 | 5 |
| minIdle | 最小空闲连接 | 否 | 5 |
| maxActive | 最大活动连接 | 否 | 20 |
| maxWait | 获取连接等待超时时间 | 否 | 60000 |
| timeBetweenEvictionRunsMillis | 空闲连接检查时间 | 否 | 60000 |
| minEvictableIdleTimeMillis | 连接最小生存时间 | 否 | 300000 |
| validationQuery | 连接检查SQL | 否 | SELECT 1 |
| testWhileIdle | 空闲时检查连接 | 否 | true |
| testOnBorrow | 获取连接时检查 | 否 | false |
| testOnReturn | 归还连接时检查 | 否 | false |

### 3. 分片规则配置

| 参数 | 说明 | 必填 | 默认值 |
|------|------|------|--------|
| shardingKey | 分片键 | 是 | - |
| dbCount | 数据库数量 | 是 | - |
| tableCount | 每个库的表数量 | 是 | - |
| strategyKey | 分片策略标识 | 是 | hash |
| strategyClass | 策略实现类的全限定名 | 否 | 默认策略 |

## 注意事项

1. **分片键选择**：
    - 尽量选择分布均匀的字段
    - 避免频繁变化的字段
    - 考虑业务查询场景

2. **使用限制**：
    - 不支持跨库事务
    - 分片键必须在SQL中存在
    - 分片数量建议为2的幂次方
    - 避免跨库JOIN查询

3. **性能优化**：
    - 合理配置连接池参数
    - 避免频繁切换数据源
    - 合理设计分片策略

## 常见问题

1. **Q: 为什么分片键值获取不到？**
   A: 确保分片键在SQL参数中存在，且名称正确。

2. **Q: 如何支持跨库查询？**
   A: 建议避免跨库查询，或使用其他方案（如数据同步）。

3. **Q: 分片数量如何选择？**
   A: 建议选择2的幂次方，便于扩展。

4. **Q: 自定义分片策略不生效？**
   A: 检查strategyClass配置是否正确，确保类能被Spring容器扫描到。

## 版本历史

- v0.0.1
    - 支持基础分库分表
    - 支持自定义分片策略
    - 支持注解方式使用

## 贡献指南

欢迎提交Issue和Pull Request。