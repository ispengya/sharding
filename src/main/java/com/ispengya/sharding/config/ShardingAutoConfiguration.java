package com.ispengya.sharding.config;

import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.route.RouteAspect;
import com.ispengya.sharding.sql.ShardingMybatisInterceptor;
import com.ispengya.sharding.sql.ShardingRewriter;
import com.ispengya.sharding.datasource.DataSourceFactory;
import com.ispengya.sharding.datasource.ShardingRoutingDataSource;
import com.ispengya.sharding.rule.ShardingRuleRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;

/**
 * @description:
 * @author: hanzhipeng
 * @create: 2025-04-20 12:16
 **/
@Slf4j
@EnableConfigurationProperties(ShardingProperties.class)
public class ShardingAutoConfiguration {

    private final ShardingProperties shardingProperties;

    public ShardingAutoConfiguration(ShardingProperties shardingProperties) {
        this.shardingProperties = shardingProperties;
        log.info("Sharding properties : {}", shardingProperties);
    }


    @Bean
    public Interceptor shardingInterceptor(ShardingRewriter shardingRewriter) {
        return new ShardingMybatisInterceptor(shardingRewriter);
    }

    @Bean
    public ShardingRuleRegistry shardingMeta() {
        return new ShardingRuleRegistry(shardingProperties);
    }

    @Bean
    public ShardingRewriter shardingRewriter(ShardingRuleRegistry shardingRuleRegistry) {
        return new ShardingRewriter(shardingRuleRegistry);
    }

    @Bean
    public RouteAspect shardingDBAspect(ShardingRuleRegistry shardingRuleRegistry) {
        return new RouteAspect(shardingRuleRegistry);
    }

    @Bean
    public DataSource dynamicDataSource() {
        Map<String, ShardingProperties.DataSourceConfig> dataSourceConfig =
                shardingProperties.getDataSources();
        Map<Object, Object> dataSourceMap = new HashMap<>();

        if (CollectionUtils.isEmpty(dataSourceConfig)) {
            throw new ShardingException("sharding datasource properties is empty");
        }

        for (Map.Entry<String, ShardingProperties.DataSourceConfig> entry : dataSourceConfig.entrySet()) {
            DataSource dataSource = DataSourceFactory.create(entry.getKey(), entry.getValue());
            dataSourceMap.put(entry.getKey(), dataSource);
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
