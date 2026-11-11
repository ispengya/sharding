package com.ispengya.sharding.algorithm;

import com.ispengya.sharding.exception.ShardingException;
import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 范围类内置算法测试：range_month 按区间映射数据源、range_day 按 epoch 天数取模分表。
 */
public class RangeAlgorithmTest {

    private final RangeMonthShardingAlgorithm rangeMonth = new RangeMonthShardingAlgorithm();
    private final RangeDayShardingAlgorithm rangeDay = new RangeDayShardingAlgorithm();

    private Map<String, String> monthParams() {
        Map<String, String> params = new HashMap<String, String>();
        params.put("2026-01~2026-06", "ds_0");
        params.put("2026-07~2026-12", "ds_1");
        return params;
    }

    @Test
    public void rangeMonthResolvesFromParams() {
        assertEquals("ds_0", rangeMonth.calculateDb(
                ShardingContext.forDb("2026-03-15", Arrays.asList("ds_0", "ds_1"), monthParams())));
        assertEquals("ds_1", rangeMonth.calculateDb(
                ShardingContext.forDb("2026-11-07", Arrays.asList("ds_0", "ds_1"), monthParams())));
        // yyyy-MM 形式同样支持
        assertEquals("ds_1", rangeMonth.calculateDb(
                ShardingContext.forDb("2026-07", Arrays.asList("ds_0", "ds_1"), monthParams())));
    }

    @Test(expected = ShardingException.class)
    public void rangeMonthMissThrows() {
        rangeMonth.calculateDb(ShardingContext.forDb("2027-01", Arrays.asList("ds_0", "ds_1"), monthParams()));
    }

    @Test(expected = ShardingException.class)
    public void rangeMonthTargetOutsideDatabasesThrows() {
        Map<String, String> params = monthParams();
        params.put("2026-01~2026-06", "ds_9");
        rangeMonth.calculateDb(ShardingContext.forDb("2026-02", Arrays.asList("ds_0", "ds_1"), params));
    }

    @Test(expected = ShardingException.class)
    public void rangeMonthMissingParamsThrows() {
        rangeMonth.calculateDb(ShardingContext.forDb("2026-01", Arrays.asList("ds_0", "ds_1"), null));
    }

    @Test
    public void rangeDayModuloTableCount() {
        String value = "2026-11-07";
        int expectedIdx = (int) Math.floorMod(LocalDate.parse(value).toEpochDay(), 30);
        String actual = rangeDay.calculateTable(ShardingContext.forTable(value, "t_order", 30, null));
        assertEquals("t_order_" + expectedIdx, actual);
        assertTrue(actual.startsWith("t_order_"));
    }

    @Test(expected = ShardingException.class)
    public void rangeDayInvalidDateThrows() {
        rangeDay.calculateTable(ShardingContext.forTable("not-a-date", "t_order", 30, null));
    }

    @Test(expected = ShardingException.class)
    public void rangeMonthRejectsTableSide() {
        rangeMonth.calculateTable(ShardingContext.forTable("2026-11-07", "t_order", 30, null));
    }

    @Test(expected = ShardingException.class)
    public void rangeDayRejectsDbSide() {
        rangeDay.calculateDb(ShardingContext.forDb("2026-11-07", Arrays.asList("ds_0", "ds_1"), null));
    }
}
