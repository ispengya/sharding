package com.ispengya.sharding.algorithm;

import com.ispengya.sharding.exception.ShardingException;

import java.util.Map;

/**
 * 按月范围分片算法（内置，Bean 名 "range_month"），仅用于数据源规则。
 * 键值取前 7 位（yyyy-MM），在 algorithmParams 的区间映射中查找目标数据源，
 * 例："2026-01~2026-06" -> "ds_1"。找不到区间或结果不在 databases 内均报错。
 */
public class RangeMonthShardingAlgorithm implements ShardingAlgorithm {

    @Override
    public String calculate(ShardingContext context) {
        Object value = context.getShardingValue();
        if (value == null) {
            throw new ShardingException("分片键值为空，无法计算 range_month 分片");
        }
        String month = String.valueOf(value);
        if (month.length() < 7) {
            throw new ShardingException("range_month 需要 yyyy-MM 或 yyyy-MM-dd 形式的键值: " + month);
        }
        month = month.substring(0, 7);

        Map<String, String> params = context.getAlgorithmParams();
        if (params == null || params.isEmpty()) {
            throw new ShardingException("range_month 缺少 algorithmParams 区间映射");
        }

        for (Map.Entry<String, String> entry : params.entrySet()) {
            String[] range = entry.getKey().split("~");
            if (range.length != 2) {
                throw new ShardingException("range_month 区间格式应为 yyyy-MM~yyyy-MM: " + entry.getKey());
            }
            if (month.compareTo(range[0].trim()) >= 0 && month.compareTo(range[1].trim()) <= 0) {
                String target = entry.getValue();
                if (context.getDatabases() != null && !context.getDatabases().contains(target)) {
                    throw new ShardingException("range_month 映射目标不在分片范围内: " + target);
                }
                return target;
            }
        }
        throw new ShardingException("range_month 未找到覆盖 " + month + " 的区间映射");
    }
}
