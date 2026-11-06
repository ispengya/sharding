package com.ispengya.sharding.util;

import java.lang.reflect.Field;
import java.util.Map;

public class ParamUtils {

    public static Object getParamValue(Object param, String key) {
        if (param == null || key == null) return null;

        if (param instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) param;

            // 常见 key 尝试
            if (map.containsKey(key)) return map.get(key);

            // 常见 fallback key（无注解的情况）
            if (map.containsKey("param1")) return map.get("param1");
            if (map.containsKey("_parameter")) return map.get("_parameter");
        }

        // fallback 反射（单对象传参）
        try {
            Field field = param.getClass().getDeclaredField(key);
            field.setAccessible(true);
            return field.get(param);
        } catch (Exception e) {
            return null;
        }
    }
}