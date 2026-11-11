package com.ispengya.sharding.annotation;

/**
 * 读写路由角色：MASTER 走主库（默认），SLAVE 走从库组内负载均衡。
 * 由 @DBRouter.route 指定（方法级 > 类级）。
 */
public enum Route {

    /**
     * 主库（默认）：读写都走 master
     */
    MASTER,

    /**
     * 从库：仅用于读，多从库间按 loadBalance 均衡；活动事务内使用会报错
     */
    SLAVE
}
