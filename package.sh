#!/bin/bash
echo "=============== Packaging ==============="
mvn clean package

if [ $? -eq 0 ]; then
    echo "Packaging successful!"
    echo "JAR file: target/cilexec-1.0.3-SNAPSHOT.jar"
    echo ""
    echo "Current time: $(java -cp target/cilexec-1.0.3-SNAPSHOT.jar com.follarce.Main)"
else
    echo "Packaging failed!"
    exit 1
fi
echo "=============== Packaging Complete ==============="
