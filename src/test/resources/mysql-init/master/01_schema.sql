-- 物理表结构（与集成测试保持一致）；db_1/db_2 由环境变量 MYSQL_DATABASE 创建
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
