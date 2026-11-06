-- 与 H2 基线测试保持一致的物理表结构（t_order_0 / t_order_1）
CREATE TABLE IF NOT EXISTS t_order_0 (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64),
    order_id VARCHAR(64)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS t_order_1 (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64),
    order_id VARCHAR(64)
) ENGINE = InnoDB;
