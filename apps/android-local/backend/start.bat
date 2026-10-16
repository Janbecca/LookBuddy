@echo off
chcp 65001 >nul 2>&1
set PYTHONUTF8=1
title LookBuddy Backend
cd /d "%~dp0"
if exist ".venv\Scripts\python.exe" (
    ".venv\Scripts\python.exe" app.py %*
) else (
    python app.py %*
)
if errorlevel 1 (
    echo.
    echo Startup failed. Install dependencies as described in README.md.
    pause
)
