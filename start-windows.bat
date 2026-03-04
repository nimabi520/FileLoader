@echo off
setlocal enabledelayedexpansion
cd /d %~dp0

set LOG_FILE=%CD%\fileloader.log

echo [FileLoader] 工作目录: %CD%

where java >nul 2>nul
if errorlevel 1 (
  echo 未找到 java，请先安装 JDK 17+。
  goto :END
)

where javac >nul 2>nul
if errorlevel 1 (
  echo 未找到 javac，请先安装 JDK（不是 JRE）。
  goto :END
)

if not exist bin mkdir bin

echo [FileLoader] 正在编译 Java 源码...
set SOURCES=
for %%f in (*.java) do set SOURCES=!SOURCES! "%%f"

if "%SOURCES%"=="" (
  echo 当前目录没有找到 .java 文件。
  goto :END
)

javac -encoding UTF-8 -cp "lib/*" -d bin %SOURCES%
if errorlevel 1 (
  echo 编译失败，请检查上方错误信息。
  goto :END
)

echo [FileLoader] 启动 MonitorUI...
start "" /B javaw -cp "bin;lib/*" MonitorUI

echo [FileLoader] 已后台启动，窗口即将自动关闭。
timeout /t 1 >nul
endlocal
exit /b 0

:END
echo.
pause
endlocal
