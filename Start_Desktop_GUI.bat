@echo off
title HKMTECH Card & Document Printer (Desktop GUI)
cd /d "%~dp0"
python aadhaar_printer_desktop.py
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo An error occurred running the Python desktop app.
    pause
)
