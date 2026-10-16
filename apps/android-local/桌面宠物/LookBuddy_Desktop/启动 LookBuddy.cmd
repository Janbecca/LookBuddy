@echo off
chcp 65001 >nul
cd /d "%~dp0"
set "ELECTRON=%~dp0node_modules\electron\dist\electron.exe"
where node >nul 2>nul
if not errorlevel 1 (
  if not exist "%ELECTRON%" (
    call npm.cmd install
    if errorlevel 1 (
      pause
      exit /b 1
    )
  )
  call npm.cmd start
  if errorlevel 1 pause
  exit /b %errorlevel%
)
if not exist "%ELECTRON%" (
  echo 请先安装 Node.js，或在此目录完成 npm install。
  pause
  exit /b 1
)
set ELECTRON_RUN_AS_NODE=1
"%ELECTRON%" node_modules\vite\bin\vite.js build
if errorlevel 1 (
  set ELECTRON_RUN_AS_NODE=
  pause
  exit /b 1
)
set ELECTRON_RUN_AS_NODE=
"%ELECTRON%" .
if errorlevel 1 pause
