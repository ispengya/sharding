#!/usr/bin/env bash
# JMH 微基准：JDK 8 构建 shaded benchmarks.jar -> 运行 -> 结果落 target/jmh-result/
# 用法: bash scripts/perf.sh [JMH 透传参数, 如 "RewriterBenchmark" 或 "-t 2"]
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

mvn -q -Pperf clean compile \
  dependency:build-classpath -Dmdep.outputFile=target/perf-classpath.txt -Dmdep.includeScope=runtime \
  -Dmaven.test.skip=true
mkdir -p target/jmh-result

# JMH 需要独立 JVM 分叉，直接 java -cp 运行（-jar 会忽略 classpath）
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) CP_SEP=';' ;;
  *) CP_SEP=':' ;;
esac
CP="target/classes${CP_SEP}$(cat target/perf-classpath.txt)"
"$JAVA_BIN" -cp "$CP" org.openjdk.jmh.Main "$@" \
  -rf json -rff target/jmh-result/jmh.json

echo
echo "结果: target/jmh-result/jmh.json"
