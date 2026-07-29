# CilExec Java 源码扩展开发

## 1. 这套扩展系统解决什么问题

CilExec 允许有能力的开发者在**仍持有项目源代码时**加入 Java 扩展。扩展可以：

- 注册新的 FCL 命名空间和函数；
- 注册函数所需的外部副作用处理器；
- 在当前 FCL 语句的 PostgreSQL 事务内读写 CilExec 仓储；
- 把自己的状态写入当前进程的持久化 continuation；
- 通过 effect journal 等待外部操作，恢复后继续同一个表达式。

它不是运行期插件系统。CilExec 不扫描插件目录，不使用 `ServiceLoader` 发现扩展，
不接收 JAR 路径，也没有安装、卸载、热更新 Java 扩展的终端函数。唯一入口是源码中的
[`SourceExtensionIndex.java`](../src/main/java/com/follarce/extension/SourceExtensionIndex.java)。
索引在 JVM 初始化时被编译成一个不可变清单。

因此，扩展加入成品的流程固定为：

```text
编写 Java 源码 → 在 SourceExtensionIndex 显式登记 → 测试 → 重新构建 JAR/镜像
```

JAR 或镜像形成后，CilExec 自身没有再修改扩展集合的能力。需要增删扩展时必须回到源码，
重新测试并构建新的系统版本。

> “封闭”指没有受支持的运行期加载入口，不是对宿主机管理员的防篡改承诺。能替换 JAR、
> 改写镜像层或控制容器运行参数的人仍能替换整个程序。正式部署应使用只读容器文件系统、
> 镜像 digest 和受控镜像仓库。

## 2. 目录和职责

| 位置 | 职责 |
| --- | --- |
| `com.follarce.extension.api` | 扩展开发者使用的公开 Java 契约。 |
| `JavaExtensionCatalog` | 校验声明、冻结清单，并把函数/副作用接入 Runtime。 |
| `SourceExtensionIndex` | 唯一的生产扩展源码清单。 |
| 扩展自己的包 | 扩展实现；建议使用组织名，例如 `com.acme.cilexec`。 |

一个扩展实现 `CilExecExtension`，返回固定描述并在 `register` 中声明函数和副作用。
扩展实例及副作用处理器可能被多个虚拟线程同时使用，类字段必须不可变或线程安全。

## 3. 最小函数扩展

新建 `src/main/java/com/acme/cilexec/GreetingExtension.java`：

```java
package com.acme.cilexec;

import com.follarce.extension.api.CilExecExtension;
import com.follarce.extension.api.ExtensionDescriptor;
import com.follarce.extension.api.ExtensionRegistrar;

public final class GreetingExtension implements CilExecExtension {
    @Override
    public ExtensionDescriptor descriptor() {
        return new ExtensionDescriptor(
                "acme.greeting",
                "1.0.0",
                "Acme greeting functions"
        );
    }

    @Override
    public void register(ExtensionRegistrar registrar) {
        registrar.function("greeting", "hello", context -> {
            context.requireArity(1);
            return "Hello, " + context.argument(0);
        }, "hi");
    }
}
```

然后只在 `SourceExtensionIndex.sourceExtensions()` 中加入构造器：

```java
private static List<CilExecExtension> sourceExtensions() {
    return List.of(
            new GreetingExtension()
    );
}
```

重新构建后函数已经注册到 Runtime，可直接在 FCL 中使用；`import` 专用于按
`.db` 文件 SHA-256 导入 FCL 包，不能用于 Java 扩展：

```fcl
message = greeting.hello("CilExec")
shortMessage = greeting.hi("developer")
```

扩展命名空间不能占用 CilExec 内置命名空间，也不能与另一扩展的限定函数名或别名重复。
非法名称和重复注册会在清单构造或 Runtime 绑定时直接失败，不会静默覆盖原函数。

## 4. 持久化状态和数据库写入

`ExtensionFunctionContext` 提供：

| API | 含义 |
| --- | --- |
| `arguments()` / `argument(i)` | 当前 FCL 参数。 |
| `processUid()` / `pid()` / `ownerId()` | 当前持久进程和用户身份。 |
| `expressionId()` / `executionEpoch()` | 当前表达式和执行围栏身份。 |
| `now()` | 本语句固定使用的 Runtime 时间。 |
| `state()` | 扩展私有、随 continuation 持久化的状态。 |
| `transaction()` | 当前语句事务的仓储视图，没有 `commit`、`rollback`、`close`。 |
| `awaitEffect(...)` | 持久登记外部副作用，暂停并在结果到达后恢复。 |

持久计数器示例：

```java
registrar.function("counter", "next", context -> {
    context.requireArity(0);
    long previous = context.state().find("value")
            .map(Number.class::cast)
            .map(Number::longValue)
            .orElse(0L);
    long next = previous + 1;
    context.state().put("value", next);
    return next;
});
```

`state()` 自动以扩展 ID 隔离键名，并由现有 continuation 编码器保存。允许的值与 FCL
值相同：`null`、字符串、布尔值、数字、数组和字符串键对象。不要放入连接、流、线程、
文件句柄、任意 Java 对象或只能在内存中识别的实例。

`transaction()` 暴露现有 domain repository，但不允许扩展自己结束事务。通过它完成的数据库
写入与 continuation、进程状态、调度队列在同一次提交中可见。事务回滚时这些写入也一起回滚。
扩展仍须使用当前 `ownerId` 做授权和资源归属，不得绕过 capability、RLS 或审计。

## 5. 外部副作用

网络请求、宿主文件、消息发送、子进程等数据库外操作不能直接写在 FCL 函数回调中。
正确方式是让函数调用 `awaitEffect`，并登记匹配的 `ExtensionEffectHandler`：

```java
import com.follarce.extension.api.ExtensionEffectHandler;
import com.follarce.extension.api.ExtensionEffectPolicy;
import java.util.Map;
import java.util.Optional;

registrar.function("delivery", "send", context -> {
    context.requireArity(1);
    return context.awaitEffect(
            "acme.delivery-send",
            Map.of("message", context.argument(0)),
            ExtensionEffectPolicy.manual()
    );
});

registrar.effect(new ExtensionEffectHandler() {
    @Override
    public String effectType() {
        return "acme.delivery-send";
    }

    @Override
    public Object execute(Object request, Optional<String> idempotencyKey) throws Exception {
        // 在这里调用真正的外部系统，并返回可持久化的 FCL 值。
        return Map.of("accepted", true);
    }
});
```

效果请求、进程的 `WAITING_EFFECT` 状态和 continuation 在一个数据库事务中提交；提交前
effect worker 看不到请求。处理器在数据库事务之外运行，结果重新写入 effect journal，随后
投递给同一个持久进程。表达式恢复后，再次调用 `awaitEffect` 会消费已投递结果，不会新建请求。

恢复策略：

| 策略 | 崩溃后的行为 | 适用条件 |
| --- | --- | --- |
| `manual()` | 已开始但结果未知时不自动重试；保留 `UNKNOWN`。 | 宁可人工处理，也不能自动重复。 |
| `retryIdempotent(key)` | 结果未知时允许自动重试。 | 远端必须真实保存并去重这个 key。只在本地声明不算幂等。 |
| `queryRemote()` | 先调用处理器的 `queryOutcome` 查询远端结果。 | 远端必须能按持久身份确认原操作结果。 |

不能在任意两个独立系统之间仅靠本地 Java 代码保证“恰好一次”。若远端没有幂等键或结果查询，
应使用 `manual()`：它避免自动重复，但崩溃窗口内可能需要管理员确认成功或失败。

## 6. 持久化开发规范

CilExec 无法从语言层面证明第三方 Java 代码遵守持久化模型，所以扩展作者必须遵守以下规则：

1. `register` 只能声明函数和处理器，不得联网、写文件、启动线程或读取变化中的环境状态。
2. FCL 函数回调可能因事务冲突或崩溃而重新执行；除 `state()` 和 `transaction()` 内操作外，
   应当把它当作可重放计算。
3. 禁止用 `static` 字段、实例字段、`ThreadLocal`、缓存或后台线程保存语义状态。
4. 数据库外操作必须使用 `awaitEffect`，不得直接在函数回调里发送请求或执行命令。
5. 仓储写入、状态更新和审计应留在同一个语句事务中；不要自行获取 JDBC 连接。
6. 返回值、副作用请求、副作用结果和扩展状态都必须是可编码的 FCL 值。
7. 处理器必须线程安全，并设置明确的超时和数据量上限；不可无限等待或无限读入内存。
8. 处理器收到幂等键时，只有把它传给远端并由远端去重后，才能声明可重试幂等。
9. 扩展升级若改变持久状态格式，应保留向后读取或提供受测试的数据库迁移。
10. 扩展不得依赖未提交数据在其他线程、连接或外部系统中立即可见。

## 7. 依赖、构建和发布

扩展需要第三方库时，把依赖作为普通 Maven 依赖加入项目 `pom.xml`。它会随 CilExec 一起锁定、
测试并打入最终 shaded JAR；不要在运行期下载 Java 依赖。推荐流程：

```bash
mvn clean test
mvn clean package
java -jar target/cilexec-app.jar terminal
```

使用容器时必须显式重建：

```bash
./Install.sh --rebuild
```

`system.extensions()` 返回本构建已经封装的扩展 ID、版本和说明；`system.ls()` 返回包含扩展在内
的实际函数名称。扩展版本应该随行为或持久格式变化而更新，并和 CilExec 镜像 digest 一起记录。

## 8. 发布前检查表

- 扩展 ID、函数命名空间、函数名、副作用类型稳定且没有冲突；
- 普通路径、参数错误、权限拒绝、事务回滚和 Runtime 崩溃均有测试；
- 不存在内存语义状态或函数内直接外部副作用；
- `manual`、`retryIdempotent`、`queryRemote` 的选择与远端真实能力一致；
- 处理器线程安全、有超时和大小限制；
- 数据库写入使用当前用户身份并保留必要审计；
- `mvn clean test` 通过，镜像按 digest 发布并以只读文件系统运行。
