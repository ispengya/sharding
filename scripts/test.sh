#!/usr/bin/env bash
# 一键测试：起真实 MySQL 环境（三节点）-> 等健康 -> 跑全量测试
# 用法: bash scripts/test.sh [mvn 透传参数, 如 -Dtest=...]
set -e
cd "$(dirname "$0")/.."

# 本机默认 java 为 17，与 Boot 2.1.3 管理的 Lombok 不兼容，自动切 JDK 8
if ! java -version 2>&1 | grep -q '"1\.8'; then
  export JAVA_HOME="D:/Dev/jdk/jdk8"
fi

docker compose -f src/test/resources/docker-compose.yml up -d --wait
mvn test "$@"
