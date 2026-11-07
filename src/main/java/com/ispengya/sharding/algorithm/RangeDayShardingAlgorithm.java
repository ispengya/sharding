package com.ispengya.sharding.algorithm;

import com.ispengya.sharding.annotation.Algorithm;
import com.ispengya.sharding.exception.ShardingException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 按天分片算法（内置，算法名 "range_day"），仅用于分表规则（scope = TABLE）。
 * 键值需为 yyyy-MM-dd 日期，按距 epoch 的天数对 tableCount 取模，
 * 返回 {logicTableName}_{下标}。键值无法解析为日期时直接报错。
 */
@Algorithm(value = "range_day", scope = Algorithm.Scope.TABLE)
public class RangeDayShardingAlgorithm implements ShardingAlgorithm {

    @Override
    public String calculateDb(ShardingContext context) {
        throw new ShardingException("range_day 仅用于分表规则（@Algorithm.scope = TABLE）");
    }

    @Override
    public String calculateTable(ShardingContext context) {
        Object value = context.getShardingValue();
        if (value == null) {
            throw new ShardingException("分片键值为空，无法计算 range_day 分片");
        }

        LocalDate date;
        try {
            date = LocalDate.parse(String.valueOf(value));
        } catch (DateTimeParseException e) {
            throw new ShardingException("range_day 需要 yyyy-MM-dd 形式的键值: " + value);
        }

        int size = context.getTableCount();
        if (size <= 0) {
            throw new ShardingException("分表数量非法: " + size);
        }
        int idx = (int) Math.floorMod(date.toEpochDay(), size);
        return context.getLogicTableName() + "_" + idx;
    }
}
