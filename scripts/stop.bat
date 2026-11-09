@echo off
:: =============================================================================
::  scripts\stop.bat - stops the backend and frontend windows opened by start.bat
::  Only the two titled windows are closed (with their process tree), never every
::  java.exe / node.exe on the machine.
:: =============================================================================
chcp 65001 > nul

echo.
echo  ============================================================
echo   L1 Support Copilot - stopping services
echo  ============================================================
echo.

echo [STOP] Backend window...
taskkill /FI "WINDOWTITLE eq Backend - Spring Boot*" /T /F >nul 2>&1 && echo        stopped || echo        not running

echo [STOP] Frontend window...
taskkill /FI "WINDOWTITLE eq Frontend - Angular*" /T /F >nul 2>&1 && echo        stopped || echo        not running

echo.
echo  [OK] Done. Services started through Git Bash are stopped with scripts/stop.sh.
echo.
pause
