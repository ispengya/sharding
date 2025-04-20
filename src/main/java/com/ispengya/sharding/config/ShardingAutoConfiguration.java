package com.ispengya.sharding.config;

import com.alibaba.druid.pool.DruidDataSource;
import com.ispengya.sharding.common.error.ShardingException;
import com.ispengya.sharding.common.tool.DbConfigValidator;
import com.ispengya.sharding.sharding.ShardingDBAspect;
import com.ispengya.sharding.sharding.ShardingRewriter;
import com.ispengya.sharding.sharding.ShardingRuleRegistry;
import com.ispengya.sharding.sharding.ShardingMybatisInterceptor;
import com.ispengya.sharding.sharding.ShardingRoutingDataSource;
import com.ispengya.sharding.sharding.ShardingTableVisitor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
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
@Configuration
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
    public ShardingDBAspect shardingDBAspect(ShardingRuleRegistry shardingRuleRegistry) {
        return new ShardingDBAspect(shardingRuleRegistry);
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
            DataSource dataSource = initDataSource(entry.getKey(), entry.getValue());
            dataSourceMap.put(entry.getKey(), dataSource);
        }

        String primary = shardingProperties.getPrimary();
        ShardingRoutingDataSource shardingRoutingDataSource = new ShardingRoutingDataSource(primary);
        shardingRoutingDataSource.setDefaultTargetDataSource(dataSourceMap.get(primary));
        shardingRoutingDataSource.setTargetDataSources(dataSourceMap);
        return shardingRoutingDataSource;
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

    private DataSource initDataSource(String dbName, ShardingProperties.DataSourceConfig dataSourceConfig) {
        DbConfigValidator.validate(dbName, dataSourceConfig);

        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setDriverClassName(dataSourceConfig.getDriverClassName());
        dataSource.setUrl(dataSourceConfig.getUrl());
        dataSource.setUsername(dataSourceConfig.getUsername());
        dataSource.setPassword(dataSourceConfig.getPassword());

        ShardingProperties.ConnectionConfig connection = dataSourceConfig.getConnection();

        dataSource.setInitialSize(connection.getInitialSize());
        dataSource.setMinIdle(connection.getMinIdle());
        dataSource.setMaxActive(connection.getMaxActive());
        dataSource.setMaxWait(connection.getMaxWait());
        dataSource.setValidationQuery(connection.getValidationQuery());
        dataSource.setTestWhileIdle(connection.isTestWhileIdle());
        dataSource.setTestOnBorrow(connection.isTestOnBorrow());
        dataSource.setTestOnReturn(connection.isTestOnReturn());
        dataSource.setTimeBetweenEvictionRunsMillis(connection.getTimeBetweenEvictionRunsMillis());
        dataSource.setMinEvictableIdleTimeMillis(connection.getMinEvictableIdleTimeMillis());
        return dataSource;
    }

}
