@echo off
chcp 65001 >nul
echo =============== Running CilExec ===============

if exist target\* rmdir /s /q target

echo Compiling...
call mvn clean compile
if %ERRORLEVEL% NEQ 0 (
    echo Compilation failed!
    exit /b 1
)

echo Checking dependencies...
call mvn dependency:copy-dependencies -q

set "CLASSPATH=target\classes"
for %%j in (target\dependency\*.jar) do (
    set "CLASSPATH=!CLASSPATH!;%%j"
)

echo Starting CilExec...
java -cp "%CLASSPATH%" com.follarce.Main

echo =============== Execution Finished ===============
