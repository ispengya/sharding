package com.ispengya.sharding.sharding;

import com.ispengya.sharding.ShardingFlag;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * @description:
 * @author: hanzhipeng
 * @create: 2025-04-20 14:15
 **/
@Aspect
@Component
@Order(1)
public class ShardingGlobalAspect {

    @Around("@annotation(shardingFlag)")
    public Object around(ProceedingJoinPoint point, ShardingFlag shardingFlag) throws Throwable {
        try {
            return point.proceed();
        } finally {
            ShardingContextHolder.clearDbHolder();
        }
    }
}
