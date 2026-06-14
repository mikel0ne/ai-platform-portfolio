@echo off
:: =============================================================================
::  scripts\stop.bat — Para todos los procesos Java (Spring Boot) y Node (Angular)
::  Doble clic para detener ambos servicios.
:: =============================================================================
chcp 65001 > nul

echo.
echo  ============================================================
echo   L1 Support Copilot — PARAR servicios
echo  ============================================================
echo.

echo [STOP] Buscando procesos Java (Spring Boot)...
for /f "tokens=2" %%i in ('tasklist /fi "imagename eq java.exe" /fo list ^| findstr PID') do (
    echo        Matando java.exe PID %%i
    taskkill /PID %%i /F >nul 2>&1
)

echo [STOP] Buscando procesos Node (Angular)...
for /f "tokens=2" %%i in ('tasklist /fi "imagename eq node.exe" /fo list ^| findstr PID') do (
    echo        Matando node.exe PID %%i
    taskkill /PID %%i /F >nul 2>&1
)

echo.
echo  [OK] Servicios detenidos.
echo.
pause
