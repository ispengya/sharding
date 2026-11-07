package com.ispengya.sharding.datasource;

import com.ispengya.sharding.annotation.Route;
import com.ispengya.sharding.config.ShardingProperties;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.loadbalance.LoadBalancer;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 逻辑数据源选取口：路由链定型为"分片选数据源 → 组内选角色"。
 * 输入路由上下文（分库键结果 + 读写角色），输出物理节点 key（{组}#master 或 {组}#slave#{下标}）：
 * 1. 分片层：dbHolder 为空落 primary，否则用分片算法给出的逻辑数据源名
 * 2. 角色层：MASTER（或未设置）→ master；SLAVE → 组内按 loadBalance 选从库，
 *    该组未配 slaves 时降级按 master 处理（文档已定，不报错）
 * 构造时校验各组 loadBalance 引用的策略存在（启动期失败快）。
 */
public class RouteResolver {

    private final String primary;
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final Map<String, LoadBalancer> loadBalances;

    public RouteResolver(String primary, Map<String, ShardingProperties.DataSourceGroup> dataSources,
                         Map<String, LoadBalancer> loadBalances) {
        this.primary = primary;
        this.loadBalances = loadBalances == null ? new LinkedHashMap<String, LoadBalancer>() : loadBalances;
        if (dataSources != null) {
            for (Map.Entry<String, ShardingProperties.DataSourceGroup> entry : dataSources.entrySet()) {
                ShardingProperties.DataSourceGroup group = entry.getValue();
                List<ShardingProperties.NodeConfig> slaves = group.getSlaves();
                int slaveCount = slaves == null ? 0 : slaves.size();
                String loadBalance = group.getLoadBalance();
                if (StringUtils.isBlank(loadBalance) || !this.loadBalances.containsKey(loadBalance.trim())) {
                    throw new ShardingException("数据源 " + entry.getKey()
                            + " 引用了不存在的负载均衡策略: " + loadBalance);
                }
                this.groups.put(entry.getKey(), new Group(slaveCount, loadBalance.trim()));
            }
        }
    }

    /**
     * 计算物理节点 key（纯函数式入口，便于单测）。
     *
     * @param dbHolder 分片层结果（null/空 → primary）
     * @param route    读写角色（null → MASTER）
     */
    public String resolve(String dbHolder, Route route) {
        String logical = StringUtils.isBlank(dbHolder) ? primary : dbHolder;
        Group group = groups.get(logical);
        if (route == Route.SLAVE && group != null && group.slaveCount > 0) {
            LoadBalancer loadBalancer = loadBalances.get(group.loadBalance);
            int index = loadBalancer.select(logical, group.slaveCount);
            return slaveKey(logical, index);
        }
        return masterKey(logical);
    }

    public static String masterKey(String logicalName) {
        return logicalName + "#master";
    }

    public static String slaveKey(String logicalName, int index) {
        return logicalName + "#slave#" + index;
    }

    private static final class Group {
        private final int slaveCount;
        private final String loadBalance;

        private Group(int slaveCount, String loadBalance) {
            this.slaveCount = slaveCount;
            this.loadBalance = loadBalance;
        }
    }
}
