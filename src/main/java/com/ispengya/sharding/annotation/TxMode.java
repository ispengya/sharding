package com.ispengya.sharding.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 跨数据源事务模式：标注于事务入口方法或类（方法级 > 类级），
 * 仅在活动事务内生效——不开事务、只定模式；未处于事务中时标注不生效也不报错。
 * 单数据源写与跨数据源只读无需标注。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TxMode {

    /**
     * 协调模式（必填）：Mode.LOCAL / Mode.XA / Mode.THREE_PC
     */
    Mode value();
}
