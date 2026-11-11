package com.ispengya.sharding.route;

import org.junit.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * 分片键提取行为基线（ShardingKeyExtractor）：
 * 覆盖 mapper 参数的主要形态——单标量（含不加 @Param 的 Long / 数字 / 日期时间）、
 * POJO 字段反射、Map 精确命中、多参数 ParamMap 兜底（POJO 值 / Map 值），
 * 以及刻意不视为标量的形态（枚举 / 集合 / 数组：宁可取不到报错，不静默错误路由）。
 */
public class ShardingKeyExtractorTest {

    public static class Order {
        private String orderId = "o-100";
        private String userId = "u-100";
    }

    enum Status { OK }

    // ---------- 单标量参数：即键值本身（对齐 MyBatis 单简单参数语义） ----------

    @Test
    public void singleLongReturnedAsIs() {
        // 修复缺口：getById(Long userId) 不加 @Param——MyBatis 任意名绑定整值，这里同样路由
        assertEquals(42L, ShardingKeyExtractor.extract(42L, "userId"));
    }

    @Test
    public void singleIntegerReturnedAsIs() {
        assertEquals(7, ShardingKeyExtractor.extract(7, "userId"));
    }

    @Test
    public void singleBigDecimalReturnedAsIs() {
        Object value = new BigDecimal("99.9");
        assertSame(value, ShardingKeyExtractor.extract(value, "userId"));
    }

    @Test
    public void singleUtilDateReturnedAsIs() {
        Object value = new Date(0L);
        assertSame(value, ShardingKeyExtractor.extract(value, "userId"));
    }

    @Test
    public void singleJavaTimeValueReturnedAsIs() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 11, 10, 0);
        assertSame(now, ShardingKeyExtractor.extract(now, "userId"));
    }

    @Test
    public void singleStringStillReturnedAsIs() {
        assertEquals("u-1", ShardingKeyExtractor.extract("u-1", "userId"));
    }

    // ---------- 刻意不视为标量：取不到 → 调用方报错（fail-fast） ----------

    @Test
    public void enumNotTreatedAsScalar() {
        // 枚举 identity hashCode 跨 JVM 不稳定，宁可不路由
        assertNull(ShardingKeyExtractor.extract(Status.OK, "userId"));
    }

    @Test
    public void collectionNotScalar() {
        // 单 List 参数（IN 查询）：不猜值，取不到由调用方报错
        assertNull(ShardingKeyExtractor.extract(java.util.Arrays.asList("u-1"), "userId"));
    }

    @Test
    public void arrayNotScalar() {
        assertNull(ShardingKeyExtractor.extract(new Long[]{1L}, "userId"));
    }

    // ---------- POJO / Map 直接提取 ----------

    @Test
    public void pojoFieldReflection() {
        assertEquals("u-100", ShardingKeyExtractor.extract(new Order(), "userId"));
    }

    @Test
    public void mapExactKeyHit() {
        Map<String, Object> param = new HashMap<String, Object>();
        param.put("userId", "u-1");
        assertEquals("u-1", ShardingKeyExtractor.extract(param, "userId"));
    }

    // ---------- 多参数 ParamMap 兜底 ----------

    @Test
    public void pojoValueInMapFallback() {
        // 多参数方法：ParamMap 值中的 POJO 反射取字段（既有行为保持）
        Map<String, Object> param = new HashMap<String, Object>();
        param.put("order", new Order());
        param.put("param1", new Order());
        assertEquals("u-100", ShardingKeyExtractor.extract(param, "userId"));
    }

    @Test
    public void mapValueInMapFallback() {
        // 修复缺口：多参数方法中的 Map 参数按键再取一层（此前被直接跳过导致报错）
        Map<String, Object> cond = new HashMap<String, Object>();
        cond.put("userId", "u-9");
        Map<String, Object> param = new HashMap<String, Object>();
        param.put("cond", cond);
        param.put("limit", 10);
        assertEquals("u-9", ShardingKeyExtractor.extract(param, "userId"));
    }

    @Test
    public void stringValueInMapFallbackStillSkipped() {
        // Map 值中的 String 不参与兜底：无法区分属于哪个键
        Map<String, Object> param = new HashMap<String, Object>();
        param.put("foo", "x");
        param.put("bar", "y");
        assertNull(ShardingKeyExtractor.extract(param, "userId"));
    }

    @Test
    public void missingEverywhereReturnsNull() {
        Map<String, Object> cond = new HashMap<String, Object>();
        cond.put("other", 1);
        Map<String, Object> param = new HashMap<String, Object>();
        param.put("cond", cond);
        assertNull(ShardingKeyExtractor.extract(param, "userId"));
        assertNull(ShardingKeyExtractor.extract(null, "userId"));
        assertNull(ShardingKeyExtractor.extract(new Order(), null));
    }
}
