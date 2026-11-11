package com.ispengya.sharding.rule;

import com.ispengya.sharding.algorithm.HashShardingAlgorithm;
import com.ispengya.sharding.algorithm.RangeDayShardingAlgorithm;
import com.ispengya.sharding.algorithm.RangeMonthShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingAlgorithm;
import com.ispengya.sharding.config.ShardingConfig;
import com.ispengya.sharding.exception.ShardingException;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 启动期校验测试：数据源引用、算法存在性、规则形状的每条失败路径，
 * 以及 rules 为空时的合法直通状态。
 */
public class ShardingRuleRegistryValidationTest {

    private Map<String, ShardingAlgorithm> algorithms() {
        Map<String, ShardingAlgorithm> algorithms = new HashMap<String, ShardingAlgorithm>();
        algorithms.put("hash", new HashShardingAlgorithm());
        return algorithms;
    }

    private ShardingConfig validProperties() {
        ShardingConfig properties = new ShardingConfig();
        properties.setPrimary("ds_0");
        ShardingConfig.DataSourceGroup ds0 = new ShardingConfig.DataSourceGroup();
        ds0.setMaster(new ShardingConfig.NodeConfig());
        ShardingConfig.DataSourceGroup ds1 = new ShardingConfig.DataSourceGroup();
        ds1.setMaster(new ShardingConfig.NodeConfig());
        properties.getDataSources().put("ds_0", ds0);
        properties.getDataSources().put("ds_1", ds1);

        ShardingConfig.ShardingRuleConfig ruleConfig = new ShardingConfig.ShardingRuleConfig();
        ShardingConfig.DbRuleConfig dbRule = new ShardingConfig.DbRuleConfig();
        dbRule.setDatabases(Arrays.asList("ds_0", "ds_1"));
        dbRule.setShardingKey("userId");
        dbRule.setAlgorithm("hash");
        ShardingConfig.TableRuleConfig tableRule = new ShardingConfig.TableRuleConfig();
        tableRule.setShardingKey("orderId");
        tableRule.setAlgorithm("hash");
        tableRule.setTableCount(2);
        ruleConfig.setDb(dbRule);
        ruleConfig.setTable(tableRule);
        properties.getRules().put("t_order", ruleConfig);
        return properties;
    }

    // ---------- 合法路径 ----------

    @Test
    public void validPropertiesBuildRules() {
        ShardingRuleRegistry registry = new ShardingRuleRegistry(validProperties(), algorithms());
        assertNotNull(registry.getDbRules().get("t_order"));
        assertNotNull(registry.getTableRules().get("t_order"));
        assertTrue(registry.getLogicTableNames().contains("t_order"));
        assertEquals(Arrays.asList("userId"), registry.getDbShardingKeys());
        assertNotNull(registry.findDbRuleByKey("userId"));
    }

    @Test
    public void emptyRulesAllowedAndMeansAllStraightToPrimary() {
        ShardingConfig properties = validProperties();
        properties.getRules().clear();
        ShardingRuleRegistry registry = new ShardingRuleRegistry(properties, algorithms());
        assertTrue(registry.getDbRules().isEmpty());
        assertTrue(registry.getTableRules().isEmpty());
    }

    // ---------- 数据源校验 ----------

    @Test(expected = ShardingException.class)
    public void dataSourcesRequired() {
        ShardingConfig properties = new ShardingConfig();
        properties.setPrimary("ds_0");
        new ShardingRuleRegistry(properties, algorithms());
    }

    @Test(expected = ShardingException.class)
    public void primaryRequired() {
        ShardingConfig properties = validProperties();
        properties.setPrimary(null);
        new ShardingRuleRegistry(properties, algorithms());
    }

    @Test(expected = ShardingException.class)
    public void primaryMustReferenceDefinedDatasource() {
        ShardingConfig properties = validProperties();
        properties.setPrimary("ds_99");
        new ShardingRuleRegistry(properties, algorithms());
    }

    @Test(expected = ShardingException.class)
    public void masterRequiredPerGroup() {
        ShardingConfig properties = validProperties();
        properties.getDataSources().get("ds_1").setMaster(null);
        new ShardingRuleRegistry(properties, algorithms());
    }

    // ---------- 规则校验 ----------

    @Test(expected = ShardingException.class)
    public void ruleMustHaveDbOrTableSection() {
        ShardingConfig properties = validProperties();
        properties.getRules().put("t_order", new ShardingConfig.ShardingRuleConfig());
        new ShardingRuleRegistry(properties, algorithms());
    }

    @Test(expected = ShardingException.class)
    public void dbDatabasesRequired() {
        ShardingConfig properties = validProperties();
        properties.getRules().get("t_order").getDb().setDatabases(null);
        new ShardingRuleRegistry(properties, algorithms());
    }

    @Test(expected = ShardingException.class)
    public void dbDatabasesMustReferenceDefinedDatasource() {
        ShardingConfig properties = validProperties();
        properties.getRules().get("t_order").getDb().setDatabases(Arrays.asList("ds_0", "ghost"));
        new ShardingRuleRegistry(properties, algorithms());
    }

    @Test(expected = ShardingException.class)
    public void dbShardingKeyRequired() {
        ShardingConfig properties = validProperties();
        properties.getRules().get("t_order").getDb().setShardingKey(" ");
        new ShardingRuleRegistry(properties, algorithms());
    }

    @Test(expected = ShardingException.class)
    public void algorithmMustExist() {
        ShardingConfig properties = validProperties();
        properties.getRules().get("t_order").getDb().setAlgorithm("magic");
        new ShardingRuleRegistry(properties, algorithms());
    }

    @Test(expected = ShardingException.class)
    public void tableOnlyAlgorithmRejectedInDbSection() {
        ShardingConfig properties = validProperties();
        properties.getRules().get("t_order").getDb().setAlgorithm("range_day");
        Map<String, ShardingAlgorithm> algorithms = algorithms();
        algorithms.put("range_day", new RangeDayShardingAlgorithm());
        new ShardingRuleRegistry(properties, algorithms);
    }

    @Test(expected = ShardingException.class)
    public void dbOnlyAlgorithmRejectedInTableSection() {
        ShardingConfig properties = validProperties();
        properties.getRules().get("t_order").getTable().setAlgorithm("range_month");
        Map<String, ShardingAlgorithm> algorithms = algorithms();
        algorithms.put("range_month", new RangeMonthShardingAlgorithm());
        new ShardingRuleRegistry(properties, algorithms);
    }

    @Test(expected = ShardingException.class)
    public void tableCountMustBePositive() {
        ShardingConfig properties = validProperties();
        properties.getRules().get("t_order").getTable().setTableCount(0);
        new ShardingRuleRegistry(properties, algorithms());
    }

    @Test(expected = ShardingException.class)
    public void tableShardingKeyRequired() {
        ShardingConfig properties = validProperties();
        properties.getRules().get("t_order").getTable().setShardingKey(null);
        new ShardingRuleRegistry(properties, algorithms());
    }
}
