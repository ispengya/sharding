package com.ispengya.sharding.exception;

/**
 * @description:
 * @author: hanzhipeng
 * @create: 2025-04-20 12:19
 **/
public class ShardingException extends RuntimeException {
    public ShardingException(String message) {
        super(message);
    }
}
