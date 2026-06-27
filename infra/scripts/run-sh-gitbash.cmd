@echo off
REM ===========================================================================
REM  run-sh-gitbash.cmd
REM  Wrapper para ejecutar un script bash del repo con Git Bash EN WINDOWS.
REM  Necesario porque IntelliJ Sh plugin lanza el comando en PowerShell por
REM  defecto (no ejecuta .sh); se usa para TODOS los scripts del repo, tanto
REM  los que hablan con binarios de Windows (psql) como los que necesitan
REM  kubectl/helm/docker (deploy.sh, setup-app-ingress.sh...).
REM
REM  Busca bash.exe en rutas conocidas (Git for Windows o cmder).
REM
REM  Uso desde la raiz del repo:
REM    .\infra\scripts\run-sh-gitbash.cmd .\l1-copilot\scripts\seed-l1-copilot-db.sh
REM ===========================================================================
setlocal
if "%~1"=="" (
  echo Uso: run-sh-gitbash.cmd ^<ruta-al-script.sh^> [args...] 1^>^&2
  exit /b 64
)

set "BASH="
if exist "C:\Program Files\Git\bin\bash.exe" set "BASH=C:\Program Files\Git\bin\bash.exe"
if not defined BASH if exist "C:\Program Files (x86)\Git\bin\bash.exe" set "BASH=C:\Program Files (x86)\Git\bin\bash.exe"
if not defined BASH if exist "C:\Program Files\cmder\vendor\git-for-windows\bin\bash.exe" set "BASH=C:\Program Files\cmder\vendor\git-for-windows\bin\bash.exe"
if not defined BASH (
  echo [x] No encuentro bash.exe de Git for Windows ni de cmder. 1^>^&2
  echo     Instala Git for Windows o ajusta la ruta en run-sh-gitbash.cmd. 1^>^&2
  exit /b 127
)

set "ARGS=%*"
set "ARGS=%ARGS:\=/%"
echo [run-sh-gitbash] "%BASH%" -c "%ARGS%"
"%BASH%" -c "%ARGS%"
exit /b %ERRORLEVEL%
