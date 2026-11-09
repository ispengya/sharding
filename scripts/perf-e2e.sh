#!/usr/bin/env bash
# 端到端压测：起真实 MySQL 三节点 -> 编译 harness -> 跑场景
# 用法: bash scripts/perf-e2e.sh [--scenario=shard-read --threads=8 --warmup=10 --duration=30]
#   不带 --scenario 跑全部场景；场景见 E2ePerfHarness
set -e
cd "$(dirname "$0")/.."

# 本机默认 java 为 17，与 Boot 2.1.3 管理的 Lombok 不兼容，自动切 JDK 8
if ! java -version 2>&1 | grep -q '"1\.8'; then
  export JAVA_HOME="D:/Dev/jdk/jdk8"
fi
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
else
  JAVA_BIN="java"
fi

docker compose -f src/test/resources/docker-compose.yml up -d --wait

mvn -q test-compile \
  dependency:build-classpath -Dmdep.outputFile=target/perf-classpath-test.txt -Dmdep.includeScope=test \
  -DskipTests

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) CP_SEP=';' ;;
  *) CP_SEP=':' ;;
esac
CP="target/classes${CP_SEP}target/test-classes${CP_SEP}$(cat target/perf-classpath-test.txt)"
"$JAVA_BIN" -cp "$CP" com.ispengya.sharding.perf.E2ePerfHarness "$@"
