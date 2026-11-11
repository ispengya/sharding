#!/usr/bin/env bash
# 一键测试（双 JDK 矩阵）：
#   起项目自带 MySQL 三节点（docker/docker-compose.yml，容器 sharding-test-*）
#     -> [1/2] JDK 8 腿：sharding-core + sharding-boot2-starter（javac8 严格 8 编译 + java8 运行）
#     -> [2/2] JDK 17 腿：全 reactor（含 sharding-boot3-starter 的 Boot 3 集成测试）
#   结束自动 down -v 清理：测试环境不持久化，每次起为全新初始化
# 用法: bash scripts/test.sh [mvn 透传参数, 如 -Dtest=...]
# 环境自适应：Git Bash(MINGW) / WSL(经 cmd.exe 调 Windows Maven) / 原生 Linux 与 macOS
set -e
cd "$(dirname "$0")/.."

COMPOSE="docker/docker-compose.yml"
export JAVA8_HOME="${JAVA8_HOME:-D:/Dev/jdk/jdk8}"
export JAVA17_HOME="${JAVA17_HOME:-D:/Dev/jdk/java17}"

ENV_KIND="unix"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) ENV_KIND="mingw" ;; esac
case "$(uname -r)" in *[Mm]icrosoft*) if [ "$ENV_KIND" = "unix" ]; then ENV_KIND="wsl"; fi ;; esac
if [ "$ENV_KIND" = "wsl" ]; then
  # WSL：mvn 为 Linux 脚本无法驱动 Windows JDK，改经 cmd.exe 调 Windows Maven
  MVN="cmd.exe /c mvn"
else
  MVN="mvn"
fi

# 测试环境即起即新：先清理旧容器/卷（不存在时静默），再全新拉起，杜绝半新半旧状态
docker compose -f "$COMPOSE" down -v --remove-orphans > /dev/null 2>&1 || true
docker compose -f "$COMPOSE" up -d --wait
trap 'docker compose -f "$COMPOSE" down -v --remove-orphans > /dev/null 2>&1 || true' EXIT

echo ""
echo "=== [1/2] JDK 8 腿：sharding-core + sharding-boot2-starter ==="
JAVA_HOME="$JAVA8_HOME" $MVN test -pl sharding-core,sharding-boot2-starter "$@"

echo ""
echo "=== [2/2] JDK 17 腿：全 reactor（含 sharding-boot3-starter）==="
JAVA_HOME="$JAVA17_HOME" $MVN test "$@"
