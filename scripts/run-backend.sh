#!/usr/bin/env bash
# 后端启动脚本：固定工作目录为 backend/，保证相对路径（../.env.local、
# ../liveproof-demo/owned-demo）正确解析。
#
# 用法：scripts/run-backend.sh [额外 Spring 参数...]
# 示例：scripts/run-backend.sh --server.port=8081
#       scripts/run-backend.sh --server.port=8081 \
#         --spring.datasource.url="jdbc:mysql://127.0.0.1:3306/liveproof_test?..."
set -euo pipefail

JAVA_HOME="${JAVA_HOME:-D:/Java/jdk-21}"
BACKEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../backend" && pwd)"
JAR_NAME="liveproof-backend-1.0.0.jar"

if [[ ! -f "$BACKEND_DIR/target/$JAR_NAME" ]]; then
  echo "未找到构建产物，请先执行：scripts/mvn.sh -f backend/pom.xml package" >&2
  exit 1
fi

cd "$BACKEND_DIR"
# 用相对路径传给 Windows java.exe，避免 MSYS 的 /d/... 风格路径无法解析
exec "$JAVA_HOME/bin/java.exe" -jar "target/$JAR_NAME" "$@"
