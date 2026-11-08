@echo off
title HKMTECH Card & Document Printer (Auto-Scan)
cd /d "%~dp0"

echo ========================================================
echo   HKMTECH - Card & Document Printer (Epson L3250)
echo ========================================================
echo.
echo Starting scanner bridge server...

REM Kill any old instance of server.py on port 8765
for /f "tokens=5" %%a in ('netstat -aon ^| findstr :8765 ^| findstr LISTENING') do taskkill /F /PID %%a >nul 2>&1

REM Start server in background
start /B python server.py >nul 2>&1

REM Wait 1 second for server to initialize
timeout /t 1 /nobreak >nul

set "TARGET_URL=http://127.0.0.1:8765/index.html"

echo Launching app...

REM Try Microsoft Edge App Mode
if exist "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" (
    start "" "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" --app="%TARGET_URL%" --window-size=1280,840
    exit /b
)

REM Try Google Chrome App Mode
if exist "C:\Program Files\Google\Chrome\Application\chrome.exe" (
    start "" "C:\Program Files\Google\Chrome\Application\chrome.exe" --app="%TARGET_URL%" --window-size=1280,840
    exit /b
)

REM Default browser
start "" "%TARGET_URL%"
exit /b
