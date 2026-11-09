@echo off
:: =============================================================================
::  scripts\start.bat - starts the L1 Copilot backend (Spring Boot) and frontend (Angular)
::  Double-click it to launch both services in separate windows,
::  or run it from IntelliJ: right click -> Run 'start.bat'
:: =============================================================================
chcp 65001 > nul
setlocal

:: Repo root (one level above scripts\)
set "SCRIPTS_DIR=%~dp0"
for %%I in ("%SCRIPTS_DIR%..") do set "ROOT=%%~fI"
set "BACKEND=%ROOT%\l1-copilot\backend"
set "FRONTEND=%ROOT%\l1-copilot\frontend"

echo.
echo  ============================================================
echo   L1 Support Copilot - local start (Windows)
echo  ============================================================
echo.

:: ── Check mvn ────────────────────────────────────────────────────────────────
where mvn >nul 2>&1
if errorlevel 1 (
    echo [ERROR] 'mvn' not found on PATH.
    echo         Install Maven and make sure it is on PATH.
    pause
    exit /b 1
)

:: ── Check npm ────────────────────────────────────────────────────────────────
where npm >nul 2>&1
if errorlevel 1 (
    echo [ERROR] 'npm' not found on PATH.
    echo         Install Node.js from https://nodejs.org
    pause
    exit /b 1
)

:: ── Install frontend dependencies when missing ───────────────────────────────
if not exist "%FRONTEND%\node_modules" (
    echo [INFO]  node_modules not found. Running npm install...
    pushd "%FRONTEND%"
    call npm install
    popd
)

:: ── Prefer Git Bash and start.sh ─────────────────────────────────────────────
::    (Git Bash usually lives in C:\Program Files\Git\bin\bash.exe)
set "BASH="
if exist "C:\Program Files\Git\bin\bash.exe"  set "BASH=C:\Program Files\Git\bin\bash.exe"
if exist "C:\Program Files (x86)\Git\bin\bash.exe" set "BASH=C:\Program Files (x86)\Git\bin\bash.exe"

if defined BASH (
    echo [INFO]  Git Bash found. Launching start.sh...
    echo.
    start "L1 Support Copilot" "%BASH%" --login -i "%SCRIPTS_DIR%start.sh"
    goto :open_browser
)

:: ── Fallback: one CMD window per process ─────────────────────────────────────
echo [INFO]  Git Bash not found. Opening separate CMD windows...
echo.

echo [BACK]  Starting Spring Boot on http://localhost:8079
start "Backend - Spring Boot" cmd /k "cd /d "%BACKEND%" && echo Starting backend... && mvn spring-boot:run"

:: Short pause so the backend starts before the frontend
timeout /t 5 /nobreak > nul

echo [FRONT] Starting Angular on http://localhost:4199
start "Frontend - Angular" cmd /k "cd /d "%FRONTEND%" && echo Starting frontend... && npm start"

:open_browser
:: ── Wait and open the browser ────────────────────────────────────────────────
echo.
echo  Waiting 20 seconds before opening the browser...
timeout /t 20 /nobreak > nul

echo  Opening the browser...
start "" "http://localhost:4199"

echo.
echo  ============================================================
echo   Back  : http://localhost:8079/api
echo   Swagger: http://localhost:8079/api/swagger-ui/index.html
echo   Front  : http://localhost:4199
echo  ============================================================
echo.
echo  You can close this window. The services keep running
echo  in their own windows (scripts\stop.bat closes them).
echo.
pause
endlocal
