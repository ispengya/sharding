package com.ispengya.sharding.testsupport;

import com.ispengya.sharding.algorithm.ShardingAlgorithm;
import com.ispengya.sharding.algorithm.ShardingContext;
import com.ispengya.sharding.annotation.Algorithm;

/**
 * 算法收集器测试夹具。
 * 刻意不放在 com.ispengya.sharding.algorithm 包：自动装配会组件扫描该包（含 test-classes），
 * 带 @Algorithm 的夹具会被误注册进集成测试上下文。
 */
public final class AlgorithmFixtures {

    private AlgorithmFixtures() {
    }

    @Algorithm("custom_a")
    public static class AlgorithmA implements ShardingAlgorithm {
        @Override
        public String calculateDb(ShardingContext context) {
            return "ds_0";
        }

        @Override
        public String calculateTable(ShardingContext context) {
            return context.getLogicTableName() + "_0";
        }
    }

    @Algorithm("custom_b")
    public static class AlgorithmB implements ShardingAlgorithm {
        @Override
        public String calculateDb(ShardingContext context) {
            return "ds_1";
        }

        @Override
        public String calculateTable(ShardingContext context) {
            return context.getLogicTableName() + "_1";
        }
    }

    /** 未标注 @Algorithm 的实现 */
    public static class BareAlgorithm implements ShardingAlgorithm {
        @Override
        public String calculateDb(ShardingContext context) {
            return "ds_0";
        }

        @Override
        public String calculateTable(ShardingContext context) {
            return context.getLogicTableName() + "_0";
        }
    }

    @Algorithm("custom_c")
    public static class DuplicateFirst implements ShardingAlgorithm {
        @Override
        public String calculateDb(ShardingContext context) {
            return "ds_0";
        }

        @Override
        public String calculateTable(ShardingContext context) {
            return context.getLogicTableName() + "_0";
        }
    }

    @Algorithm("custom_c")
    public static class DuplicateSecond implements ShardingAlgorithm {
        @Override
        public String calculateDb(ShardingContext context) {
            return "ds_1";
        }

        @Override
        public String calculateTable(ShardingContext context) {
            return context.getLogicTableName() + "_1";
        }
    }

    @Algorithm("  ")
    public static class BlankNameAlgorithm implements ShardingAlgorithm {
        @Override
        public String calculateDb(ShardingContext context) {
            return "ds_0";
        }

        @Override
        public String calculateTable(ShardingContext context) {
            return context.getLogicTableName() + "_0";
        }
    }
}
