package com.ispengya.sharding.tx;

import com.ispengya.sharding.annotation.Mode;

/**
 * 事务协调上下文（线程绑定）：
 * mode = 当前事务的协调模式（@TxMode 切面写入，入口帧设置、帧结束还原，内层不覆盖）；
 * crossDatasourceWritten = 本线程存在已写入 ≥2 个数据源且未关闭的事务连接
 * （供 REQUIRES_NEW 挂起外层时的跨库判定）。
 * 生命周期：mode 由切面 save/restore；flag 由逻辑连接 close/abort 按写名单清理。
 */
public final class TxContextHolder {

    private static final ThreadLocal<Mode> MODE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CROSS_DS_WRITTEN = new ThreadLocal<>();

    private TxContextHolder() {
    }

    public static Mode getMode() {
        return MODE.get();
    }

    public static void setMode(Mode mode) {
        MODE.set(mode);
    }

    public static void clearMode() {
        MODE.remove();
    }

    public static boolean isCrossDatasourceWritten() {
        return Boolean.TRUE.equals(CROSS_DS_WRITTEN.get());
    }

    public static void markCrossDatasourceWritten() {
        CROSS_DS_WRITTEN.set(Boolean.TRUE);
    }

    public static void clearCrossDatasourceWritten() {
        CROSS_DS_WRITTEN.remove();
    }
}
