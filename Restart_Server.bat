@echo off
title Restart HKMTECH Scanner Bridge
cd /d "%~dp0"
echo ========================================================
echo   Restarting HKMTECH Scanner Bridge Server...
echo ========================================================
echo.

echo Terminating any active server processes on port 8767...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8767 ^| findstr LISTENING') do (
    taskkill /PID %%a /F >nul 2>&1
)

timeout /t 1 /nobreak >nul
echo Starting server fresh with latest updates...
call "%~dp0Start_App.bat"
