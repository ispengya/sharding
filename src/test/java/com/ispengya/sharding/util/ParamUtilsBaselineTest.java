package com.ispengya.sharding.util;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 分片键提取行为基线（ParamUtils）。
 */
public class ParamUtilsBaselineTest {

    public static class Order {
        private String orderId = "o-100";
        private String userId = "u-100";
    }

    @Test
    public void mapKeyHit() {
        Map<String, Object> param = new HashMap<String, Object>();
        param.put("userId", "u-1");
        assertEquals("u-1", ParamUtils.getParamValue(param, "userId"));
    }

    @Test
    public void mapNoParam1FallbackWhenKeyAbsent() {
        // 阶段 2 修复（已知问题 #9）：不再兜底返回 param1，仅精确匹配，取不到返回 null
        Map<String, Object> param = new HashMap<String, Object>();
        param.put("param1", "first");
        assertNull(ParamUtils.getParamValue(param, "userId"));
    }

    @Test
    public void beanFieldReflection() {
        assertEquals("o-100", ParamUtils.getParamValue(new Order(), "orderId"));
    }

    @Test
    public void missingReturnsNull() {
        assertNull(ParamUtils.getParamValue(new HashMap<String, Object>(), "userId"));
        assertNull(ParamUtils.getParamValue(new Order(), "notExist"));
        assertNull(ParamUtils.getParamValue(null, "userId"));
        assertNull(ParamUtils.getParamValue(new Order(), null));
    }
}
