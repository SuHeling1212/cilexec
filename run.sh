#!/bin/bash

echo "=============== Running CilExec ==============="

# Check if target/classes exists
if [ ! -d "target/classes" ]; then
    echo "Compiled class files not found, compiling..."
    mvn clean compile
    if [ $? -ne 0 ]; then
        echo "Compilation failed!"
        exit 1
    fi
fi

# Copy dependencies to target/dependency
echo "Checking dependencies..."
if [ ! -d "target/dependency" ]; then
    mvn dependency:copy-dependencies -q
fi

# Get dependency classpath
CLASSPATH="target/classes"

# Add dependency jars
for jar in target/dependency/*.jar; do
    CLASSPATH="$CLASSPATH:$jar"
done

# Run the program
echo "Starting CilExec..."
java -cp "$CLASSPATH" com.follarce.Main

echo "=============== Execution Finished ==============="
