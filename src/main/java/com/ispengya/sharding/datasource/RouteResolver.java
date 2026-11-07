package com.ispengya.sharding.datasource;

import org.apache.commons.lang3.StringUtils;

/**
 * 逻辑数据源选取口：路由上下文键 + primary → 逻辑数据源名。
 * 全部数据源选取逻辑收敛于此方法——阶段 3 的组内主从选择将在此处插入。
 */
public final class RouteResolver {

    private RouteResolver() {
    }

    public static String resolve(String contextKey, String primary) {
        if (StringUtils.isBlank(contextKey)) {
            return primary;
        }
        return contextKey;
    }
}
