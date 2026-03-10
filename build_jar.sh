#!/bin/bash
set -e

# 设置目录变量
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
DIST_DIR="${PROJECT_DIR}/dist"
TEMP_DIR="${DIST_DIR}/temp"
JAR_FILE="${DIST_DIR}/FileLoader.jar"
LIB_DIR="${PROJECT_DIR}/lib"

echo "[Build] 正在准备构建目录..."
rm -rf "$TEMP_DIR"
mkdir -p "$TEMP_DIR"

echo "[Build] 正在编译源代码..."
# 编译所有 Java 文件到临时目录
find "${PROJECT_DIR}" -maxdepth 1 -name "*.java" -print0 | xargs -0 javac -encoding UTF-8 -cp "${LIB_DIR}/*" -d "$TEMP_DIR"

echo "[Build] 正在解压依赖库..."
# 解压 SQLite JDBC 到临时目录，以便打入 Fat JAR
(cd "$TEMP_DIR" && jar xf "${LIB_DIR}/sqlite-jdbc-3.50.3.0.jar")
# 移除解压出来的 META-INF（防止冲突）
rm -rf "${TEMP_DIR}/META-INF"

echo "[Build] 正在打包 JAR 文件..."
# 创建 Manifest 文件
echo "Manifest-Version: 1.0" > "${DIST_DIR}/MANIFEST.MF"
echo "Main-Class: MonitorUI" >> "${DIST_DIR}/MANIFEST.MF"
echo "" >> "${DIST_DIR}/MANIFEST.MF"

# 打包
jar cmf "${DIST_DIR}/MANIFEST.MF" "$JAR_FILE" -C "$TEMP_DIR" .

echo "[Build] 正在清理临时文件..."
rm -rf "$TEMP_DIR"
rm "${DIST_DIR}/MANIFEST.MF"

# 创建启动脚本
START_SCRIPT="${DIST_DIR}/start-fileloader.command"
echo "#!/bin/bash" > "$START_SCRIPT"
echo "cd \"\$(dirname \"\$0\")\"" >> "$START_SCRIPT"
echo "java -jar FileLoader.jar" >> "$START_SCRIPT"
chmod +x "$START_SCRIPT"

echo "[Build] 构建成功！"
echo "JAR 文件: $JAR_FILE"
echo "一键启动脚本: $START_SCRIPT"
