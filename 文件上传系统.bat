@echo off
setlocal
cd /d "%~dp0"
:: 设置控制台编码为 UTF-8，避免中文乱码
chcp 65001 > nul

title 文件监控上传工具启动脚本

echo ==========================================
echo       文件监控上传工具 - Windows 启动脚本
echo ==========================================
echo.

:: 1. 检查 Java 环境
echo [1/4] 检查 Java 环境...
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未检测到 Java 环境！
    echo 请确保已安装 JDK/JRE 8 或更高版本并配置了环境变量。
    echo.
    pause
    exit /b 1
)
echo Java 环境正常。
echo.

:: 2. 编译 Java 文件
echo [2/4] 检查编译环境...
javac -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [提示] 未检测到 javac 命令，跳过编译步骤，尝试直接运行...
) else (
    echo 正在编译 Java 文件...
    :: 编译，指定 classpath 为当前目录
    javac -encoding UTF-8 -cp . *.java
    if %errorlevel% neq 0 (
        echo [错误] 编译失败！请检查源代码。
        echo.
        pause
        exit /b 1
    )
    echo 编译成功。
)
echo.

:: 3. 检查主类是否存在
if not exist MonitorUI.class (
    echo [错误] 找不到 MonitorUI.class 文件！
    echo 原因可能是：
    echo 1. 没有安装 JDK (只有 JRE) 且没有预编译的 class 文件。
    echo 2. 编译过程失败。
    echo.
    pause
    exit /b 1
)

:: 4. 运行程序
echo [3/4] 正在启动应用程序...
echo.
echo 程序启动后，当前窗口会自动关闭...
echo.

:: 使用 javaw 后台运行，不占用控制台，并明确指定 classpath
start javaw -cp . MonitorUI

:: 3秒后自动退出
timeout /t 3 > nul
exit
