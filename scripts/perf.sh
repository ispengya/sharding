#!/usr/bin/env bash
# JMH 微基准（sharding-core）：JDK 8 构建 -> 运行 -> 结果落 sharding-core/target/jmh-result/
# 用法: bash scripts/perf.sh [JMH 透传参数, 如 "RewriterBenchmark" 或 "-t 2"]
# 环境自适应：Git Bash(MINGW) / WSL(经 cmd.exe 调 Windows Maven，JVM 经 wslpath 调 Windows java) / 原生 Linux 与 macOS
set -e
cd "$(dirname "$0")/.."

# 基准跑在 JDK 8 上，与 Java 8 基线用户的运行时一致
if ! java -version 2>&1 | grep -q '"1\.8'; then
  export JAVA_HOME="D:/Dev/jdk/jdk8"
fi

ENV_KIND="unix"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) ENV_KIND="mingw" ;; esac
case "$(uname -r)" in *[Mm]icrosoft*) if [ "$ENV_KIND" = "unix" ]; then ENV_KIND="wsl"; fi ;; esac

if [ "$ENV_KIND" = "wsl" ]; then
  MVN="cmd.exe /c mvn"
  if [ -n "$JAVA_HOME" ]; then
    JAVA_BIN="$(wslpath "$JAVA_HOME")/bin/java.exe"
  else
    JAVA_BIN="java.exe"
  fi
else
  MVN="mvn"
  if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
  else
    JAVA_BIN="java"
  fi
fi

CORE=sharding-core
$MVN -q -pl $CORE -Pperf clean compile \
  dependency:build-classpath -Dmdep.outputFile=$CORE/target/perf-classpath.txt -Dmdep.includeScope=runtime \
  -Dmaven.test.skip=true
mkdir -p $CORE/target/jmh-result

# JMH 需要独立 JVM 分叉，直接 java -cp 运行（-jar 会忽略 classpath）
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) CP_SEP=';' ;;
  Linux*Microsoft*|Linux*microsoft*) CP_SEP=';' ;;   # WSL 跑的是 Windows JVM，路径分隔符仍为 ;
  *) CP_SEP=':' ;;
esac
CP="$CORE/target/classes${CP_SEP}$(cat $CORE/target/perf-classpath.txt)"
"$JAVA_BIN" -cp "$CP" org.openjdk.jmh.Main "$@" \
  -rf json -rff $CORE/target/jmh-result/jmh.json

echo
echo "结果: $CORE/target/jmh-result/jmh.json"
