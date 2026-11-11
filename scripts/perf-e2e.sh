#!/usr/bin/env bash
# 端到端压测（sharding-boot2-starter 真实链路）：
#   起项目自带 MySQL 三节点（docker/docker-compose.yml）-> 编译 harness -> 跑场景（JDK 8）
# 用法: bash scripts/perf-e2e.sh [--scenario=shard-read --threads=8 --warmup=10 --duration=30]
#   不带 --scenario 跑全部场景；场景见 E2ePerfHarness
# 环境自适应：Git Bash(MINGW) / WSL(经 cmd.exe 调 Windows Maven，JVM 经 wslpath 调 Windows java) / 原生 Linux 与 macOS
set -e
cd "$(dirname "$0")/.."

# 压测跑在 JDK 8 上，与 Java 8 基线用户的运行时一致
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

COMPOSE="docker/docker-compose.yml"
B2=sharding-boot2-starter

# 测试环境即起即新：先清理旧容器/卷（不存在时静默），再全新拉起
docker compose -f "$COMPOSE" down -v --remove-orphans > /dev/null 2>&1 || true
docker compose -f "$COMPOSE" up -d --wait

$MVN -q -pl $B2 -am test-compile \
  dependency:build-classpath -Dmdep.outputFile=$B2/target/perf-classpath-test.txt -Dmdep.includeScope=test \
  -DskipTests

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) CP_SEP=';' ;;
  Linux*Microsoft*|Linux*microsoft*) CP_SEP=';' ;;   # WSL 跑的是 Windows JVM，路径分隔符仍为 ;
  *) CP_SEP=':' ;;
esac
CP="$B2/target/classes${CP_SEP}$B2/target/test-classes${CP_SEP}$(cat $B2/target/perf-classpath-test.txt)"
"$JAVA_BIN" -cp "$CP" com.ispengya.sharding.perf.E2ePerfHarness "$@"
