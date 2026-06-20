@echo off
chcp 65001 >nul
echo =============== Packaging ===============
if exist target\* rmdir /s /q target
call mvn clean package

if %ERRORLEVEL% EQU 0 (
    echo Packaging successful!
    echo JAR file: target\cilexec-1.0.0-ALPHA-3.jar
    echo.
    echo Current time:
    java -cp target\cilexec-1.0.0-ALPHA-3.jar com.follarce.Main
) else (
    echo Packaging failed!
    exit /b 1
)
echo =============== Packaging Complete ===============
