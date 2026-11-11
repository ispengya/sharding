package com.ispengya.sharding.route;

import com.ispengya.sharding.util.ParamUtils;

import java.util.Map;

/**
 * 分片键取值原语：方法基线（RouteAspect 规范化方法参数后）与语句级路由（直接用 MyBatis parameterObject）
 * 共用同一实现，避免两套提取语义分叉。
 * 规则：
 * <ul>
 *   <li>单标量参数即键值本身：对齐 MyBatis 单简单参数语义（DefaultParameterHandler 将
 *       hasTypeHandler 类型整体绑定到任意 #{名} 占位符）——getById(Long userId) 不加 @Param 也能路由。
 *       枚举与数组刻意排除（identity hashCode 跨 JVM 不稳定，宁可取不到报错也不静默路由）</li>
 *   <li>Map 精确按键取；普通对象沿父类链反射取字段</li>
 *   <li>Map 取不到时在其值中兜底查找：值中的 POJO 反射取字段，值中的 Map 按键再取一层
 *       （覆盖多参数方法中 Map / POJO 携带分片键的场景）；String 值不参与兜底（无法区分属于哪个键，语义不明）</li>
 * </ul>
 * 取不到返回 null，由调用方决定是否报错。
 */
public final class ShardingKeyExtractor {

    private ShardingKeyExtractor() {
    }

    public static Object extract(Object source, String key) {
        if (source == null || key == null) {
            return null;
        }
        if (isScalarValue(source)) {
            return source;
        }
        Object value = ParamUtils.getParamValue(source, key);
        if (value != null) {
            return value;
        }
        if (source instanceof Map) {
            for (Object candidate : ((Map<?, ?>) source).values()) {
                if (candidate == null || candidate instanceof String) {
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

    /**
     * 单标量参数判定（包级可见，便于单测）：命中即视为键值本身。
     * 与 MyBatis 绑定单简单参数到任意占位符名的语义对齐；枚举排除见类注释。
     */
    static boolean isScalarValue(Object source) {
        if (source instanceof Enum) {
            return false;
        }
        return source instanceof String
                || source instanceof Boolean
                || source instanceof Character
                || source instanceof Number
                || source instanceof java.util.Date
                || source.getClass().getName().startsWith("java.time.");
    }
}
