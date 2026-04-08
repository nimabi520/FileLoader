#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

LOG_FILE="${SCRIPT_DIR}/fileloader.log"

echo "[FileLoader] 工作目录: $SCRIPT_DIR"

if ! command -v java >/dev/null 2>&1; then
  echo "未找到 java，请先安装 JDK 17+。"
  read -n 1 -s -r -p "按任意键关闭..."
  echo
  exit 1
fi

GRADLE_CMD=""
if [ -x "${SCRIPT_DIR}/gradlew" ]; then
  GRADLE_CMD="${SCRIPT_DIR}/gradlew"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE_CMD="gradle"
else
  echo "未找到 gradlew/gradle，请先安装 Gradle 8+ 或保留项目自带 wrapper。"
  read -n 1 -s -r -p "按任意键关闭..."
  echo
  exit 1
fi

echo "[FileLoader] 后台启动 Compose 迁移界面（日志: $LOG_FILE）..."
nohup "$GRADLE_CMD" --no-daemon run >> "$LOG_FILE" 2>&1 &

echo "[FileLoader] 启动命令已提交，窗口将自动关闭。"
sleep 1
exit 0
