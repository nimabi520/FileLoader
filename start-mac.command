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

if ! command -v javac >/dev/null 2>&1; then
  echo "未找到 javac，请先安装 JDK（不是 JRE）。"
  read -n 1 -s -r -p "按任意键关闭..."
  echo
  exit 1
fi

mkdir -p bin

echo "[FileLoader] 正在编译 Java 源码..."
find . -maxdepth 1 -name "*.java" -print0 | xargs -0 javac -encoding UTF-8 -cp "lib/*" -d bin

echo "[FileLoader] 后台启动 MonitorUI（日志: $LOG_FILE）..."
nohup java -cp "bin:lib/*" MonitorUI >> "$LOG_FILE" 2>&1 &

echo "[FileLoader] 启动命令已提交，窗口将自动关闭。"
sleep 1
exit 0
