package com.ispengya.sharding.config;

import com.ispengya.sharding.algorithm.HashShardingAlgorithm;
import com.ispengya.sharding.algorithm.RangeDayShardingAlgorithm;
import com.ispengya.sharding.algorithm.RangeMonthShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingAlgorithm;
import com.ispengya.sharding.datasource.DataSourceFactory;
import com.ispengya.sharding.datasource.ShardingRoutingDataSource;
import com.ispengya.sharding.exception.ShardingException;
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
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Spring Boot 自动装配入口：注册内置分片算法、分片规则、SQL 改写器、路由切面、
 * MyBatis 拦截器、数据源与事务管理器等 Bean，并在启动完成后校验 @DBRouter 标注位置。
 */
@Slf4j
@EnableConfigurationProperties(ShardingProperties.class)
public class ShardingAutoConfiguration {

    private final ShardingProperties shardingProperties;

    public ShardingAutoConfiguration(ShardingProperties shardingProperties) {
        this.shardingProperties = shardingProperties;
        log.info("Sharding properties : {}", shardingProperties);
    }

    // ---------- 内置分片算法（Bean 名 = 配置中 algorithm 的引用名） ----------

    @Bean
    public ShardingAlgorithm hash() {
        return new HashShardingAlgorithm();
    }

    @Bean
    public ShardingAlgorithm range_month() {
        return new RangeMonthShardingAlgorithm();
    }

    @Bean
    public ShardingAlgorithm range_day() {
        return new RangeDayShardingAlgorithm();
    }

    // ---------- 规则与核心组件 ----------

    @Bean
    public ShardingRuleRegistry shardingMeta(Map<String, ShardingAlgorithm> algorithms) {
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
    public DataSource dynamicDataSource() {
        Map<String, ShardingProperties.DataSourceGroup> groups = shardingProperties.getDataSources();
        if (CollectionUtils.isEmpty(groups)) {
            throw new ShardingException("sharding dataSources properties is empty");
        }

        Map<Object, Object> dataSourceMap = new HashMap<>();
        for (Map.Entry<String, ShardingProperties.DataSourceGroup> entry : groups.entrySet()) {
            ShardingProperties.NodeConfig master = entry.getValue().getMaster();
            if (master == null) {
                throw new ShardingException("数据源 " + entry.getKey() + " 未配置 master");
            }
            // 阶段 3 在此扩展 slaves 池的构建与组内选择
            dataSourceMap.put(entry.getKey(), DataSourceFactory.create(entry.getKey() + "-master", master));
        }

        String primary = shardingProperties.getPrimary();
        ShardingRoutingDataSource shardingRoutingDataSource = new ShardingRoutingDataSource(primary);
        shardingRoutingDataSource.setDefaultTargetDataSource(dataSourceMap.get(primary));
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
