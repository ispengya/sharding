package com.ispengya.sharding.tx;

import java.sql.SQLException;

/**
 * 3PC 阶段超时：策略层按所处阶段决定中止语义
 * （canCommit/preCommit 超时 → abort；commit 超时 → 按日志状态保持 PREPARED 交恢复）
 */
public class StageTimeoutException extends SQLException {

    private final String stage;

    public StageTimeoutException(String stage, Throwable cause) {
        super("3PC 阶段超时: " + stage, cause);
        this.stage = stage;
    }

    public String getStage() {
        return stage;
    }
}
