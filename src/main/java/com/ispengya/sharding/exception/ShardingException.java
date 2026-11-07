package com.ispengya.sharding.exception;

/**
 * 分库分表框架统一运行时异常：配置错误、路由失败等场景均抛出该类型。
 */
public class ShardingException extends RuntimeException {
    public ShardingException(String message) {
        super(message);
    }
}
