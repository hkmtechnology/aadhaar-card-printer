@echo off
title HKMTECH Card ^& Document Printer
cd /d "%~dp0"

echo ========================================================
echo   HKMTECH - Card ^& Document Printer (Epson L3250)
echo ========================================================
echo.

REM 1. Check if server is already running and responding to HTTP
curl.exe -s --max-time 1 http://127.0.0.1:8767/api/settings >nul 2>&1
if not errorlevel 1 (
    echo [OK] Scanner bridge server is already active on http://127.0.0.1:8767
    goto :launch_browser
)

REM 2. Determine Python launcher
set "PY_CMD="
where python >nul 2>&1
if not errorlevel 1 (
    set "PY_CMD=python"
) else (
    where py >nul 2>&1
    if not errorlevel 1 (
        set "PY_CMD=py"
    )
)

if "%PY_CMD%"=="" (
    echo [ERROR] Python was not found on your system PATH!
    echo Please make sure Python 3 is installed.
    echo.
    pause
    exit /b 1
)

echo Starting scanner bridge server in background...
start "HKMTECH Scanner Bridge" /min %PY_CMD% "%~dp0server.py"

REM 3. Wait up to 8 seconds for server to initialize
for /L %%i in (1,1,8) do (
    ping 127.0.0.1 -n 2 >nul
    curl.exe -s --max-time 1 http://127.0.0.1:8767/api/settings >nul 2>&1
    if not errorlevel 1 goto :server_ready
)

REM If curl.exe check failed, test netstat as fallback
netstat -ano | findstr :8767 | findstr LISTENING >nul 2>&1
if not errorlevel 1 goto :server_ready

echo [ERROR] Scanner bridge server failed to start on http://127.0.0.1:8767
echo Testing server diagnostics:
%PY_CMD% "%~dp0server.py"
echo.
pause
exit /b 1

:server_ready
echo [OK] Scanner bridge server is active on http://127.0.0.1:8767
echo.

:launch_browser
set "TARGET_URL=http://127.0.0.1:8767/index.html"
echo Launching application window...

REM 1. Try Microsoft Edge App Mode
if exist "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" (
    start "" "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" --app="%TARGET_URL%" --window-size=1280,840
    exit /b 0
)

if exist "C:\Program Files\Microsoft\Edge\Application\msedge.exe" (
    start "" "C:\Program Files\Microsoft\Edge\Application\msedge.exe" --app="%TARGET_URL%" --window-size=1280,840
    exit /b 0
)

REM 2. Try Google Chrome App Mode
if exist "C:\Program Files\Google\Chrome\Application\chrome.exe" (
    start "" "C:\Program Files\Google\Chrome\Application\chrome.exe" --app="%TARGET_URL%" --window-size=1280,840
    exit /b 0
)

if exist "C:\Program Files (x86)\Google\Chrome\Application\chrome.exe" (
    start "" "C:\Program Files (x86)\Google\Chrome\Application\chrome.exe" --app="%TARGET_URL%" --window-size=1280,840
    exit /b 0
)

REM 3. Default browser fallback
start "" "%TARGET_URL%"
exit /b 0
