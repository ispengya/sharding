#!/bin/bash
# 从库复制初始化（entrypoint 可能以 source 方式执行本脚本，不使用 set -e，失败显式 exit）
# 流程：
#   1. 等 master-1 初始化完成（TCP 可连 = 初始化结束）
#   2. 在从库手动重建 db_1 与物理表——master 初始化期的 DDL 不在当前 binlog 段，
#      按位置复制永远回放不到；手动重建后，测试期的行事件（INSERT/DELETE）照常同步
#   3. 记录 master 当前 binlog 位置 -> CHANGE MASTER -> START SLAVE
# 线程就绪由 compose 健康检查（SHOW SLAVE STATUS）把关。

echo "[slave-init] waiting for mysql-master-1 ..."
until mysql -hmysql-master-1 -uroot -proot -e "SELECT 1" >/dev/null 2>&1; do
  sleep 2
done

echo "[slave-init] creating db_1 schema on slave (init-phase DDL is outside current binlog)"
mysql -uroot -proot <<'EOF' || exit 1
CREATE DATABASE IF NOT EXISTS db_1;
USE db_1;
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
EOF

MASTER_STATUS=$(mysql -hmysql-master-1 -uroot -proot -e "SHOW MASTER STATUS\G") || exit 1
BINLOG_FILE=$(echo "$MASTER_STATUS" | awk '/File:/{print $2}')
BINLOG_POS=$(echo "$MASTER_STATUS" | awk '/Position:/{print $2}')

if [ -z "$BINLOG_FILE" ] || [ -z "$BINLOG_POS" ]; then
  echo "[slave-init] failed to get master status:"
  echo "$MASTER_STATUS"
  exit 1
fi

echo "[slave-init] master at ${BINLOG_FILE}:${BINLOG_POS}, configuring replication"
mysql -uroot -proot <<EOF || exit 1
CHANGE MASTER TO
  MASTER_HOST='mysql-master-1',
  MASTER_USER='repl',
  MASTER_PASSWORD='repl',
  MASTER_LOG_FILE='${BINLOG_FILE}',
  MASTER_LOG_POS=${BINLOG_POS};
START SLAVE;
EOF

echo "[slave-init] done; healthcheck will wait for threads"
