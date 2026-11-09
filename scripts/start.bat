@echo off
:: =============================================================================
::  scripts\start.bat — Arrancar Backend (Spring Boot) + Frontend (Angular)
::  Doble clic aqui para lanzar ambos servicios en ventanas separadas.
::  O ejecutar desde IntelliJ: boton derecho -> Run 'start.bat'
:: =============================================================================
chcp 65001 > nul
setlocal

:: Calcular raíz del proyecto (un nivel arriba de scripts\)
set "SCRIPTS_DIR=%~dp0"
for %%I in ("%SCRIPTS_DIR%..") do set "ROOT=%%~fI"
set "BACKEND=%ROOT%\l1-copilot\backend"
set "FRONTEND=%ROOT%\l1-copilot\frontend"

echo.
echo  ============================================================
echo   L1 Support Copilot — Arranque local (Windows)
echo  ============================================================
echo.

:: ── Verificar que mvn existe ─────────────────────────────────────────────────
where mvn >nul 2>&1
if errorlevel 1 (
    echo [ERROR] 'mvn' no encontrado en PATH.
    echo         Instala Maven y asegurate de que esta en PATH.
    pause
    exit /b 1
)

:: ── Verificar que npm existe ──────────────────────────────────────────────────
where npm >nul 2>&1
if errorlevel 1 (
    echo [ERROR] 'npm' no encontrado en PATH.
    echo         Instala Node.js desde https://nodejs.org
    pause
    exit /b 1
)

:: ── Instalar dependencias del frontend si hace falta ────────────────────────
if not exist "%FRONTEND%\node_modules" (
    echo [INFO]  node_modules no encontrado. Ejecutando npm install...
    pushd "%FRONTEND%"
    call npm install
    popd
)

:: ── Intentar usar Git Bash para lanzar start.sh ──────────────────────────────
::    (Git Bash suele estar en C:\Program Files\Git\bin\bash.exe)
set "BASH="
if exist "C:\Program Files\Git\bin\bash.exe"  set "BASH=C:\Program Files\Git\bin\bash.exe"
if exist "C:\Program Files (x86)\Git\bin\bash.exe" set "BASH=C:\Program Files (x86)\Git\bin\bash.exe"

if defined BASH (
    echo [INFO]  Git Bash detectado. Lanzando con start.sh...
    echo.
    start "L1 Support Copilot" "%BASH%" --login -i "%SCRIPTS_DIR%start.sh"
    goto :open_browser
)

:: ── Fallback: lanzar cada proceso en su propia ventana CMD ──────────────────
echo [INFO]  Git Bash no detectado. Abriendo ventanas CMD separadas...
echo.

echo [BACK]  Arrancando Spring Boot en http://localhost:8079
start "Backend - Spring Boot" cmd /k "cd /d "%BACKEND%" && echo Arrancando backend... && mvn spring-boot:run"

:: Pequeña pausa para que el backend arranque antes del frontend
timeout /t 5 /nobreak > nul

echo [FRONT] Arrancando Angular en http://localhost:4199
start "Frontend - Angular" cmd /k "cd /d "%FRONTEND%" && echo Arrancando frontend... && npm start"

:open_browser
:: ── Esperar y abrir el navegador ─────────────────────────────────────────────
echo.
echo  Esperando 20 segundos antes de abrir el navegador...
timeout /t 20 /nobreak > nul

echo  Abriendo navegador...
start "" "http://localhost:4199"

echo.
echo  ============================================================
echo   Back  : http://localhost:8079/api
echo   Swagger: http://localhost:8079/api/swagger-ui/index.html
echo   Front  : http://localhost:4199
echo  ============================================================
echo.
echo  Puedes cerrar esta ventana. Los servicios siguen corriendo
echo  en sus ventanas propias.
echo.
pause
endlocal
