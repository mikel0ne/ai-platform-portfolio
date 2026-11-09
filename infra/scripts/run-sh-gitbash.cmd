@echo off
REM ===========================================================================
REM  run-sh-gitbash.cmd
REM  Runs a bash script of the repo with Git Bash ON WINDOWS.
REM  Needed because the IntelliJ Sh plugin launches commands in PowerShell by
REM  default (it cannot run .sh); every script of the repo goes through it, both
REM  the ones calling Windows binaries (psql) and the ones that need
REM  kubectl/helm/docker (deploy.sh, setup-app-ingress.sh...).
REM
REM  Looks for bash.exe in known locations (Git for Windows or cmder).
REM
REM  Usage from the repo root:
REM    .\infra\scripts\run-sh-gitbash.cmd .\l1-copilot\scripts\seed-l1-copilot-db.sh
REM ===========================================================================
setlocal
if "%~1"=="" (
  echo Usage: run-sh-gitbash.cmd ^<path-to-script.sh^> [args...] 1^>^&2
  exit /b 64
)

set "BASH="
if exist "C:\Program Files\Git\bin\bash.exe" set "BASH=C:\Program Files\Git\bin\bash.exe"
if not defined BASH if exist "C:\Program Files (x86)\Git\bin\bash.exe" set "BASH=C:\Program Files (x86)\Git\bin\bash.exe"
if not defined BASH if exist "C:\Program Files\cmder\vendor\git-for-windows\bin\bash.exe" set "BASH=C:\Program Files\cmder\vendor\git-for-windows\bin\bash.exe"
if not defined BASH (
  echo [x] bash.exe from Git for Windows or cmder not found. 1^>^&2
  echo     Install Git for Windows or fix the path in run-sh-gitbash.cmd. 1^>^&2
  exit /b 127
)

set "ARGS=%*"
set "ARGS=%ARGS:\=/%"
echo [run-sh-gitbash] "%BASH%" -c "%ARGS%"
"%BASH%" -c "%ARGS%"
exit /b %ERRORLEVEL%
