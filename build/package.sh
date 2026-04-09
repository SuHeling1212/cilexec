#!/bin/bash
echo "=============== Packaging ==============="
rm -rf target/
mvn clean package

if [ $? -eq 0 ]; then
    echo "Packaging successful!"
    echo "JAR file: target/cilexec-1.0.0-ALPHA-3.jar"
    echo ""
    echo "Current time: $(java -cp target/cilexec-1.0.0-ALPHA-3.jar com.follarce.Main)"   
else
    echo "Packaging failed!"
    exit 1
fi
echo "=============== Packaging Complete ==============="
