@echo off
chcp 65001 >nul
echo =============== Packaging ===============
call mvn clean package

if %ERRORLEVEL% EQU 0 (
    echo Packaging successful!
    echo JAR file: target/cilexec-1.0.0-ALPHA.jar   
    echo.
    echo Current time:
    java -cp target/cilexec-1.0.0-ALPHA.jar com.follarce.Main
) else (
    echo Packaging failed!
    exit /b 1
)
echo =============== Packaging Complete ===============
