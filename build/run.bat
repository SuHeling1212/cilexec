@echo off
setlocal
cd /d "%~dp0\.."

if not exist target\cilexec-app.jar call build\package.bat
if errorlevel 1 exit /b %errorlevel%
if "%~1"=="" (
    java %JVM_OPTIONS% -jar target\cilexec-app.jar runtime
) else (
    java %JVM_OPTIONS% -jar target\cilexec-app.jar %*
)
