#!/bin/bash

echo "=============== Running CilExec ==============="
rm -rf target/
echo "Compiling..."
mvn clean compile
if [ $? -ne 0 ]; then
    echo "Compilation failed!"
    exit 1
fi

echo "Checking dependencies..."
mvn dependency:copy-dependencies -q

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
