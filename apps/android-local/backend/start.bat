@echo off
chcp 65001 >nul 2>&1
title LookBuddy 关心语后端
cd /d "%~dp0"
echo 正在启动 LookBuddy 关心语后端...
echo.
python app.py
pause
