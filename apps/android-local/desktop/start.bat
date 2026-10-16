@echo off
chcp 65001 >nul 2>&1
title LookBuddy Windows 桌宠
cd /d "%~dp0"
python -c "from PIL import Image" >nul 2>&1
if errorlevel 1 python -m pip install -r requirements.txt
python app.py --write-android-config emulator
python app.py
if errorlevel 1 pause
