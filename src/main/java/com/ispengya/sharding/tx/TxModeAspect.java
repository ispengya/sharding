package com.ispengya.sharding.tx;

import com.ispengya.sharding.annotation.Mode;
import com.ispengya.sharding.annotation.TxMode;
import com.ispengya.sharding.exception.ShardingException;
import com.ispengya.sharding.tx.coordinator.TransactionLog;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;

/**
 * @TxMode 切面：解析协调模式（方法级 > 类级）写入 TxContextHolder，
 * 供写入校验与 ShardingConnection 提交时选择 TxCommitStrategy。
 * 位于事务拦截器外层（LOWEST_PRECEDENCE - 1）——入口处事务尚未开启，
 * 故无条件记录模式（帧结束还原；内层再标不覆盖入口值），是否生效由
 * "是否存在活动事务"的消费点（写入校验/提交）决定：
 * 未开事务 → 模式无人消费，不生效也不报错。
 * Mode.XA 入口即校验事务日志表可用（CREATE IF NOT EXISTS，失败附 DDL）——
 * 在任何业务 SQL 之前失败快。
 */
@Slf4j
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class TxModeAspect {

    private final TransactionLog transactionLog;

    public TxModeAspect(TransactionLog transactionLog) {
        this.transactionLog = transactionLog;
    }

    @Around("(@annotation(com.ispengya.sharding.annotation.TxMode) || @within(com.ispengya.sharding.annotation.TxMode))")
    public Object doApplyMode(ProceedingJoinPoint jp) throws Throwable {
        Method method = ((MethodSignature) jp.getSignature()).getMethod();
        Class<?> targetClass = jp.getTarget() != null ? jp.getTarget().getClass() : method.getDeclaringClass();
        Mode mode = resolveMode(method, targetClass);

        Mode previous = TxContextHolder.getMode();
        if (previous == null) {
            if (mode == Mode.XA) {
                // 使用 XA 模式前校验事务日志表（首次触发建表，之后幂等直返）
                transactionLog.ensureTable();
            }
            TxContextHolder.setMode(mode);
            log.debug("事务模式记录: {} @ {}", mode, signature(method));
        }
        try {
            return jp.proceed();
        } finally {
            if (previous == null) {
                TxContextHolder.clearMode();
            } else {
                TxContextHolder.setMode(previous);
            }
        }
    }

    /**
     * 解析协调模式（包级可见，便于单测）：方法级 @TxMode > 类级，二者必居其一
     */
    Mode resolveMode(Method method, Class<?> targetClass) {
        TxMode annotation = method.getAnnotation(TxMode.class);
        if (annotation == null && targetClass != null) {
            annotation = targetClass.getAnnotation(TxMode.class);
        }
        if (annotation == null) {
            throw new ShardingException("方法 " + signature(method) + " 未标注 @TxMode");
        }
        return annotation.value();
    }

    private String signature(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }
}
