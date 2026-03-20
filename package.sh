#!/bin/bash
echo "===============正在打包==============="
mvn clean package

if [ $? -eq 0 ]; then
    echo "打包成功！"
    echo "JAR包: target/cilexec-1.0.0SNAPSHOT.jar"
    echo ""
    echo "当前时间: $(java -cp target/cilexec-1.0.0SNAPSHOT.jar com.follarce.Main)"
else
    echo "打包失败！"
    exit 1
fi
echo "===============打包完成==============="