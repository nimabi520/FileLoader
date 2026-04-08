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

set GRADLE_CMD=
if exist "%CD%\gradlew.bat" (
  set GRADLE_CMD=gradlew.bat
) else (
  where gradle >nul 2>nul
  if errorlevel 1 (
    echo 未找到 gradlew/gradle，请先安装 Gradle 8+ 或保留项目自带 wrapper。
    goto :END
  )
  set GRADLE_CMD=gradle
)

echo [FileLoader] 启动 Compose 迁移界面...
start "" /B %GRADLE_CMD% --no-daemon run

echo [FileLoader] 已后台启动，窗口即将自动关闭。
timeout /t 1 >nul
endlocal
exit /b 0

:END
echo.
pause
endlocal
