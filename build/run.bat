@echo off
chcp 65001 >nul
echo =============== Running CilExec ===============

REM Check if target/classes exists
if not exist "target\classes" (
    echo Compiled class files not found, compiling...
    call mvn clean compile
    if %ERRORLEVEL% NEQ 0 (
        echo Compilation failed!
        exit /b 1
    )
)

REM Copy dependencies to target/dependency
echo Checking dependencies...
if not exist "target\dependency" (
    call mvn dependency:copy-dependencies -q
)

REM Get dependency classpath
set "CLASSPATH=target\classes"

REM Add dependency jars
for %%j in (target\dependency\*.jar) do (
    set "CLASSPATH=!CLASSPATH!;%%j"
)

REM Run the program
echo Starting CilExec...
java -cp "%CLASSPATH%" com.follarce.Main

echo =============== Execution Finished ===============
