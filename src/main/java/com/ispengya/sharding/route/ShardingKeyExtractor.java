package com.ispengya.sharding.route;

import com.ispengya.sharding.util.ParamUtils;

import java.util.Map;

/**
 * 分库键取值原语：方法基线（RouteAspect 规范化方法参数后）与语句级路由（直接用 MyBatis parameterObject）
 * 共用同一实现，避免两套提取语义分叉。
 * 规则：单字符串参数即键值本身（与 MyBatis 单参数语义一致）；Map 精确按键取；
 * 普通对象沿父类链反射取字段；多参 ParamMap 取不到时再在其值中的 POJO 上兜底查找。
 * 取不到返回 null，由调用方决定是否报错。
 */
public final class ShardingKeyExtractor {

    private ShardingKeyExtractor() {
    }

    public static Object extract(Object source, String key) {
        if (source == null || key == null) {
            return null;
        }
        if (source instanceof String) {
            return source;
        }
        Object value = ParamUtils.getParamValue(source, key);
        if (value != null) {
            return value;
        }
        if (source instanceof Map) {
            for (Object candidate : ((Map<?, ?>) source).values()) {
                if (candidate == null || candidate instanceof String || candidate instanceof Map) {
                    continue;
                }
                value = ParamUtils.getParamValue(candidate, key);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }
}
