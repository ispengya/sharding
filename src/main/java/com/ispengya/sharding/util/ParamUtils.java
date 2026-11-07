package com.ispengya.sharding.util;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * 从 MyBatis 参数对象中按键提取值：Map 精确匹配，普通对象沿父类链反射取字段。
 * 取不到返回 null（由调用方决定报错），不做 param1 等语义不明的兜底。
 */
public class ParamUtils {

    public static Object getParamValue(Object param, String key) {
        if (param == null || key == null) {
            return null;
        }
        if (param instanceof Map) {
            return ((Map<?, ?>) param).get(key);
        }
        for (Class<?> type = param.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(key);
                field.setAccessible(true);
                return field.get(param);
            } catch (NoSuchFieldException e) {
                // 继续查父类
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }
}
