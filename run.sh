#!/bin/bash

echo "===============正在运行 CilExec==============="

# 检查 target/classes 是否存在
if [ ! -d "target/classes" ]; then
    echo "未找到编译后的类文件，正在编译..."
    mvn clean compile
    if [ $? -ne 0 ]; then
        echo "编译失败！"
        exit 1
    fi
fi

# 复制依赖到 target/dependency
echo "检查依赖..."
if [ ! -d "target/dependency" ]; then
    mvn dependency:copy-dependencies -q
fi

# 获取依赖 classpath
CLASSPATH="target/classes"

# 添加依赖 jar 包
for jar in target/dependency/*.jar; do
    CLASSPATH="$CLASSPATH:$jar"
done

# 运行程序
echo "启动 CilExec..."
java -cp "$CLASSPATH" com.follarce.Main

echo "===============运行结束==============="
