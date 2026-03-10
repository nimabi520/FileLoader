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

mkdir -p bin lib

SQLITE_JAR="${SCRIPT_DIR}/lib/sqlite-jdbc.jar"
if [ ! -f "$SQLITE_JAR" ]; then
  echo "[FileLoader] 未检测到 sqlite-jdbc 驱动，正在自动下载..."
  SQLITE_URL="https://maven.aliyun.com/repository/central/org/xerial/sqlite-jdbc/3.46.1.3/sqlite-jdbc-3.46.1.3.jar"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$SQLITE_JAR" "$SQLITE_URL" && echo "[FileLoader] sqlite-jdbc 驱动下载成功。" || echo "[FileLoader] 警告: sqlite-jdbc 驱动下载失败，将以不持久化模式运行。"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$SQLITE_JAR" "$SQLITE_URL" && echo "[FileLoader] sqlite-jdbc 驱动下载成功。" || echo "[FileLoader] 警告: sqlite-jdbc 驱动下载失败，将以不持久化模式运行。"
  else
    echo "[FileLoader] 警告: 未找到 curl/wget，无法自动下载 sqlite-jdbc 驱动，将以不持久化模式运行。"
  fi
fi

echo "[FileLoader] 正在编译 Java 源码..."
find . -maxdepth 1 -name "*.java" -print0 | xargs -0 javac -encoding UTF-8 -cp "lib/*" -d bin

echo "[FileLoader] 后台启动 MonitorUI（日志: $LOG_FILE）..."
nohup java -cp "bin:lib/*" MonitorUI >> "$LOG_FILE" 2>&1 &

echo "[FileLoader] 启动命令已提交，窗口将自动关闭。"
sleep 1
exit 0
