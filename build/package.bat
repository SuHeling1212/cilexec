@echo off
setlocal
cd /d "%~dp0\.."

set "BUILD_REVISION=unknown"
for /f %%r in ('git rev-parse --short^=12 HEAD 2^>nul') do set "BUILD_REVISION=%%r"
call mvn --batch-mode --no-transfer-progress -Dbuild.revision=%BUILD_REVISION% clean verify
if errorlevel 1 exit /b %errorlevel%
echo Created %CD%\target\cilexec-app.jar
