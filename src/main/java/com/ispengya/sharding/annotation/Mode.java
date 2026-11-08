package com.ispengya.sharding.annotation;

/**
 * 跨数据源事务协调模式（由 @TxMode 指定，方法级 > 类级）。
 * 当前仅 LOCAL 可用；XA / THREE_PC 由后续 2PC/3PC 能力提供。
 */
public enum Mode {

    /**
     * 本地模式：各分片连接循环提交，失败重试并记录已交/未交明细（无原子性，一般业务）
     */
    LOCAL,

    /**
     * 两阶段提交（2PC）：prepare 全部通过才统一 commit（尚未实现）
     */
    XA,

    /**
     * 三阶段提交（3PC）：在 2PC 基础上增加 canCommit/preCommit 与超时（尚未实现）
     */
    THREE_PC
}
