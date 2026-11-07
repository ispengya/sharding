package com.ispengya.sharding.config;

import com.ispengya.sharding.algorithm.ShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingAlgorithms;
import com.ispengya.sharding.datasource.DataSourceFactory;
import com.ispengya.sharding.datasource.RouteResolver;
import com.ispengya.sharding.datasource.ShardingRoutingDataSource;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.loadbalance.LoadBalancer;
import com.ispengya.sharding.loadbalance.LoadBalances;
import com.ispengya.sharding.route.DBRouterPlacementValidator;
import com.ispengya.sharding.route.RouteAspect;
import com.ispengya.sharding.rule.ShardingRuleRegistry;
import com.ispengya.sharding.sql.ShardingMybatisInterceptor;
import com.ispengya.sharding.sql.ShardingRewriter;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Spring Boot 自动装配入口：组件扫描 algorithm/ 与 loadbalance/ 包，
 * 按 @Algorithm / @LoadBalance 注解发现内置分片算法与负载均衡策略（与自定义统一，无手工注册）；
 * 注册分片规则、SQL 改写器、路由切面、MyBatis 拦截器、数据源与事务管理器等 Bean，
 * 启动完成后校验 @DBRouter 标注位置。
 */
@Slf4j
@EnableConfigurationProperties(ShardingProperties.class)
@ComponentScan(basePackageClasses = {ShardingAlgorithm.class, LoadBalancer.class})
public class ShardingAutoConfiguration {

    private final ShardingProperties shardingProperties;

    public ShardingAutoConfiguration(ShardingProperties shardingProperties) {
        this.shardingProperties = shardingProperties;
        log.info("Sharding properties : {}", shardingProperties);
    }

    // ---------- 规则与核心组件 ----------

    @Bean
    public ShardingRuleRegistry shardingMeta(ApplicationContext applicationContext) {
        Map<String, ShardingAlgorithm> algorithms = ShardingAlgorithms.collect(
                applicationContext.getBeansOfType(ShardingAlgorithm.class).values());
        return new ShardingRuleRegistry(shardingProperties, algorithms);
    }

    @Bean
    public ShardingRewriter shardingRewriter(ShardingRuleRegistry shardingRuleRegistry) {
        return new ShardingRewriter(shardingRuleRegistry);
    }

    @Bean
    public Interceptor shardingInterceptor(ShardingRewriter shardingRewriter) {
        return new ShardingMybatisInterceptor(shardingRewriter);
    }

    @Bean
    public RouteAspect shardingDBAspect(ShardingRuleRegistry shardingRuleRegistry) {
        return new RouteAspect(shardingRuleRegistry);
    }

    @Bean
    public ApplicationListener<ContextRefreshedEvent> dbRouterPlacementValidator() {
        return new DBRouterPlacementValidator();
    }

    // ---------- 数据源与事务 ----------

    @Bean
    public RouteResolver routeResolver(ApplicationContext applicationContext) {
        Map<String, LoadBalancer> loadBalances = LoadBalances.collect(
                applicationContext.getBeansOfType(LoadBalancer.class).values());
        return new RouteResolver(shardingProperties.getPrimary(), shardingProperties.getDataSources(), loadBalances);
    }

    @Bean
    public DataSource dynamicDataSource(RouteResolver routeResolver) {
        Map<String, ShardingProperties.DataSourceGroup> groups = shardingProperties.getDataSources();
        if (CollectionUtils.isEmpty(groups)) {
            throw new ShardingException("sharding dataSources properties is empty");
        }

        Map<Object, Object> dataSourceMap = new HashMap<>();
        for (Map.Entry<String, ShardingProperties.DataSourceGroup> entry : groups.entrySet()) {
            String name = entry.getKey();
            ShardingProperties.DataSourceGroup group = entry.getValue();
            ShardingProperties.NodeConfig master = group.getMaster();
            if (master == null) {
                throw new ShardingException("数据源 " + name + " 未配置 master");
            }
            // 每个物理节点独立建池，master 与各 slave 参数互不影响
            dataSourceMap.put(RouteResolver.masterKey(name), DataSourceFactory.create(name + "-master", master));
            if (group.getSlaves() != null) {
                for (int i = 0; i < group.getSlaves().size(); i++) {
                    dataSourceMap.put(RouteResolver.slaveKey(name, i),
                            DataSourceFactory.create(name + "-slave-" + i, group.getSlaves().get(i)));
                }
            }
        }

        ShardingRoutingDataSource shardingRoutingDataSource = new ShardingRoutingDataSource(routeResolver);
        shardingRoutingDataSource.setDefaultTargetDataSource(
                dataSourceMap.get(RouteResolver.masterKey(shardingProperties.getPrimary())));
        shardingRoutingDataSource.setTargetDataSources(dataSourceMap);
        shardingRoutingDataSource.afterPropertiesSet();
        return new LazyConnectionDataSourceProxy(shardingRoutingDataSource);
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dynamicDataSource, ShardingRewriter shardingRewriter) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dynamicDataSource);
        factoryBean.setPlugins(shardingInterceptor(shardingRewriter));
        return factoryBean.getObject();
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dynamicDataSource) {
        return new DataSourceTransactionManager(dynamicDataSource);
    }
}
