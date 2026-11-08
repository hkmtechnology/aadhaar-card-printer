@echo off
setlocal enabledelayedexpansion
title HKM Card Printer - Wireless Android Build and Install

echo ====================================================================
echo    HKM Card and Document Printer - Wireless Android Installer
echo ====================================================================
echo.

:: Ensure ADB is in PATH
where adb >nul 2>&1
if %ERRORLEVEL% neq 0 (
    if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
        set "PATH=%LOCALAPPDATA%\Android\Sdk\platform-tools;%PATH%"
    ) else (
        echo [ERROR] ADB not found. Please install Android Platform Tools.
        echo.
        pause
        exit /b 1
    )
)

:CHECK_DEVICE
echo [1/4] Checking for connected Android devices via ADB...
set "DEVICE_FOUND=0"
set "DEVICE_ID="
set "DEVICE_MODEL="

for /f "skip=1 tokens=1,2" %%A in ('adb devices') do (
    if "%%B"=="device" (
        set "DEVICE_ID=%%A"
        set "DEVICE_FOUND=1"
    )
)

if "%DEVICE_FOUND%"=="1" goto DEVICE_READY

echo.
echo [WARNING] No authorized Android device detected!
echo.
echo Current ADB Device List:
adb devices
echo.
echo Would you like to connect wirelessly?
echo   1. Connect by entering Phone IP and Port (e.g. 192.168.1.15:41235)
echo   2. Retry detection
echo   3. Exit
set /p CHOICE="Select an option (1-3) [default: 1]: "
if "%CHOICE%"=="" set "CHOICE=1"
if "%CHOICE%"=="1" goto PROMPT_WIRELESS_CONNECT
if "%CHOICE%"=="2" goto CHECK_DEVICE
goto EXIT_SCRIPT

:PROMPT_WIRELESS_CONNECT
echo.
echo On your phone:
echo   1. Go to Settings -> Developer options -> Wireless debugging
echo   2. Note the 'IP address and Port' shown
echo.
set /p PHONE_IP="Enter Phone IP and Port: "
if not "%PHONE_IP%"=="" (
    echo Connecting to %PHONE_IP%...
    adb connect %PHONE_IP%
    timeout /t 2 >nul
    goto CHECK_DEVICE
)
goto CHECK_DEVICE

:DEVICE_READY
:: Get Device Model
for /f "delims=" %%M in ('adb shell getprop ro.product.model 2^>nul') do set "DEVICE_MODEL=%%M"
if "%DEVICE_MODEL%"=="" set "DEVICE_MODEL=%DEVICE_ID%"
echo [OK] Connected to: %DEVICE_MODEL% (%DEVICE_ID%)
echo.

:: Step 2: Build the APK
echo [2/4] Building Android APK (Gradle assembleDebug)...
cd /d "%~dp0android"
call gradlew.bat assembleDebug
if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] Gradle build failed! Please check the output above.
    cd /d "%~dp0"
    pause
    exit /b 1
)
cd /d "%~dp0"

set "SOURCE_APK=%~dp0android\app\build\outputs\apk\debug\app-debug.apk"
set "LOCAL_APK=%~dp0HKM_Card_Printer.apk"

if not exist "%SOURCE_APK%" (
    echo.
    echo [ERROR] Compiled APK not found at: %SOURCE_APK%
    pause
    exit /b 1
)

copy /y "%SOURCE_APK%" "%LOCAL_APK%" >nul
echo [OK] APK ready: %LOCAL_APK%
echo.

:: Step 3: Install onto Device
echo [3/4] Installing HKM Card Printer onto %DEVICE_MODEL%...
echo.
echo NOTE: Please unlock your phone screen now.
echo If a prompt asks 'Install via USB?', tap 'INSTALL' or 'ALLOW'.
echo.

adb install -r -d "%LOCAL_APK%"
if %ERRORLEVEL% equ 0 (
    echo.
    echo [OK] Installation successful!
    goto LAUNCH_APP
)

echo.
echo [NOTICE] Direct ADB install was restricted by phone security (e.g. Xiaomi/MIUI restriction).
echo [NOTICE] Transferring APK directly to phone Downloads folder...
echo.
adb push "%LOCAL_APK%" /sdcard/Download/HKM_Card_Printer.apk
echo [OK] File copied to: Phone Downloads / HKM_Card_Printer.apk
echo Opening Package Installer on your phone screen...
adb shell am start -a android.intent.action.VIEW -d "file:///sdcard/Download/HKM_Card_Printer.apk" -t "application/vnd.android.package-archive"
echo.
echo Tap 'Install' on your phone to complete installation!

:LAUNCH_APP
:: Step 4: Launch App
echo.
echo [4/4] Launching HKM Card Printer on phone...
adb shell am start -n com.hkmtech.cardprinter/.MainActivity >nul 2>&1

echo.
echo ====================================================================
echo    ALL DONE! HKM Card Printer is ready on your Android device!
echo ====================================================================
echo.
pause
exit /b 0

:EXIT_SCRIPT
echo Exiting.
pause
exit /b 1
