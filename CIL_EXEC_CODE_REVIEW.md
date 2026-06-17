# CilExec 项目代码审查报告

> **审查日期**: 2026-06-17
> **审查范围**: 全部 Java 源代码（~7500 行）、FCL Shell 脚本（740 行）、构建配置
> **审查者**: AI 代码审查（DeepSeek V4 Flash）
> **编译状态**: ✅ Maven 编译通过

---

## 目录

1. [严重级别 Bug](#1-严重级别-bug)
2. [中等级别 Bug](#2-中等级别-bug)
3. [低级别问题 / 代码异味](#3-低级别问题--代码异味)
4. [安全漏洞](#4-安全漏洞)
5. [架构设计问题](#5-架构设计问题)
6. [缺失功能 / 未实现](#6-缺失功能--未实现)
7. [改进建议](#7-改进建议)
8. [总结](#8-总结)

---

## 1. 严重级别 Bug

### 1.1 `ProcessRunner.run()` — `clearTransientState()` 永不被调用

- **文件**: `src/main/java/com/follarce/process/ProcessRunner.java`
- **位置**: `run()` 方法，第 126-144 行
- **描述**: `clearTransientState()` 方法（第 1548 行）设计目的是在每个 tick 结束时清空内存中的瞬态状态，但 `run()` 方法的 while 循环中**从未调用过它**。这导致：
  - 每次 `loadFromFile()` → `executeLine()` → `saveToFile()` 后，`data`、`codeLines`、`blockStack` 等字段不会重置
  - 下个 tick 的 `loadFromFile()` 会重新从文件中加载，但与内存中残留的状态可能冲突
  - 长时间运行后，内存中的 `functions` map 会无限累积（已被弃用但未清理的用户函数）
- **影响**: 内存泄漏 + 潜在的状态不一致
- **修复**: 在 `run()` 循环的 `saveToFile()` 后添加 `clearTransientState()` 调用

### 1.2 `ProcessRunner.java` — 内联 Debug 日志泄露所有变量数据

- **文件**: `src/main/java/com/follarce/process/ProcessRunner.java`
- **位置**: 第 771-773 行
```java
if (expr.contains("expected") || expr.contains("actual")) {
    Logger.debug("DATA CHECK: expr=" + expr + " data=" + data + " dataKeys=" + data.keySet());
}
```
- **描述**: 这段代码会在日志中输出完整的 `data` Map，包括所有变量值。如果变量中包含密码、密钥等敏感信息，会直接写入日志文件。此外，这是调试残留代码，不应存在于生产代码中。
- **影响**: 敏感信息泄露到日志文件
- **修复**: 删除这段残留的 Debug 代码

### 1.3 `Scheduler.initialScan()` — 可能重复启动 INIT 进程

- **文件**: `src/main/java/com/follarce/process/Scheduler.java`
- **位置**: 第 53-75 行
- **描述**: `initialScan()` 方法中，扫描到 INIT 进程时先记录日志"需要手动启动"，但随后仍然执行了 `if (!runners.containsKey(pid))` 检查。虽然 Main.java 中在启动 Scheduler 之前已经手动注册了 INIT 的 runner（`Scheduler.registerRunner(Constants.PID_INIT, initRunner)`），但如果由于某种时序问题 `runners` 中尚未包含 INIT，Scheduler 会**再次启动一个 INIT runner 线程**，导致 PID 1 有两个线程在运行。
- **影响**: 资源竞争、行为不确定
- **修复**: `initialScan()` 中应明确跳过 INIT 进程

### 1.4 `ProcessRunner.handleProcessTermination()` — 子进程退出不清理父进程 Child 列表

- **文件**: `src/main/java/com/follarce/process/ProcessRunner.java`
- **位置**: 第 1576-1599 行
- **描述**: 当子进程正常终止时，只删除（或保留）自己的 `.pres` 文件，但不通知父进程从 Child 列表中移除自己。父进程的 `Child` Map 会累积已终止子进程的条目。虽然 `handleWait()` 中会通过检查子进程文件是否存在来清理，但如果父进程从**不调用 `wait()`**，这些垃圾条目将永远残留。
- **影响**: 父进程的 Child 列表内存泄漏
- **修复**: 子进程终止时，应读取父进程文件并清理 Child 列表

### 1.5 `NetworkUtil.httpGet/httpPost` — 4xx/5xx 响应的错误处理

- **文件**: `src/main/java/com/follarce/util/NetworkUtil.java`
- **位置**: 第 20、45 行
- **描述**: `conn.getInputStream()` 在 HTTP 响应码 >= 400 时会抛出 `IOException`（因为 `HttpURLConnection` 在非 2xx 时返回 error stream）。但当前的 catch 块只是返回 `"ERROR: HTTP GET failed: " + e.getMessage()`，丢失了实际的响应内容和状态码。
- **影响**: HTTP 错误请求无法获取错误响应体，全部被掩盖为"连接失败"
- **修复**: 检查 `responseCode >= 400` 时使用 `conn.getErrorStream()` 读取错误响应

### 1.6 `ProcessRunner.handleAssignment()` — `lastForkChildPid` 竞态条件

- **文件**: `src/main/java/com/follarce/process/ProcessRunner.java`
- **描述**: `handleAssignment()` 使用 `lastForkChildPid` 字段来获取 fork 子进程的 PID 以赋值给变量。但 `handleFork()` 设置此字段，`handleAssignment()` 消费它，中间没有同步保护。如果多个语句同时在执行（理论上不会，因为每个进程是单线程，但跨 tick 时可能）或者文件被外部修改，可能导致获取到错误的 PID。
- **影响**: fork 返回值偶尔错误
- **修复**: 将 fork 返回的 PID 直接通过表达式求值返回，而不是通过内部字段传递

### 1.7 `ProcessRunner.handleWhile()` — 条件判断后行号推进逻辑缺陷

- **描述**: `handleWhile()` 执行流程中，如果 while 条件为 true，进入循环体前要记录起始行到 `blockStack`。但如果循环体是空的（如 `while true {}`），跳过闭合花括号后行号可能回跳错误。此外 `countBracesInLine` 和 `handleClosingBraces` 的配合在高嵌套层级中容易出错。
- **影响**: while 循环可能提前退出或不退出
- **修复**: 需要更严谨的块栈管理和行号推进测试

---

## 2. 中等级别 Bug

### 2.1 `SwapFunctionProvider.listAllPools()` — 死代码残留

- **文件**: `src/main/java/com/follarce/function/SwapFunctionProvider.java`
- **位置**: 第 703 行
```java
String listing = FileUtil.read(swapDir + ".");
```
- **描述**: 这一行的结果 `listing` 从未被使用。它是一个死代码，并且 `swapDir + "."` 会尝试读取一个目录作为文件，可能导致异常被吞。
- **影响**: 无直接功能影响，但说明代码不完整

### 2.2 `IOFunctionProvider` 和 `UtilFunctionProvider` 功能重复

- **文件**: `IOFunctionProvider.java` 和 `UtilFunctionProvider.java`
- **描述**: 两个 Provider 都提供了 `print`、`println` 和 `input` 方法，功能完全相同。`IOFunctionProvider` 额外提供了 `readFile`/`writeFile`，`UtilFunctionProvider` 额外提供了 JSON 工具和类型检查。这会导致函数注册时的混淆。
- **影响**: 维护困难，同一功能在两个地方实现
- **修复**: 合并到同一个 Provider 或明确划分职责

### 2.3 `ProcessRunner` — `countBracesInLine` 支持单引号字符串但 Lexer 不支持

- **文件**: `src/main/java/com/follarce/process/ProcessRunner.java`
- **位置**: 第 1314 行
```java
if (c == '"' || c == '\'') {
```
- **描述**: `countBracesInLine()` 在计数花括号时支持单引号字符串（`'...'`）中的花括号不被计数。但 FCL 的 Lexer（`Lexer.java`）**不支持单引号字符串**。这导致两者不一致：用户不能使用单引号字符串语法，但花括号计数却考虑了它。
- **影响**: 不会引起运行时错误，但表明逻辑不一致

### 2.4 `FileUtil.writeAtomic()` — 没有 `AtomicMoveNotSupportedException` fallback

- **文件**: `src/main/java/com/follarce/util/FileUtil.java`
- **位置**: 第 593 行
```java
Files.move(tempFile.toPath(), realFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
```
- **描述**: `ATOMIC_MOVE` 在某些文件系统上不支持，会抛出 `AtomicMoveNotSupportedException`。当前代码会直接抛出 `RuntimeException`，没有 fallback 到非原子移动。
- **影响**: 在特定文件系统上（如某些网络文件系统）原子写入会失败
- **修复**: 捕获 `AtomicMoveNotSupportedException`，fallback 到 `StandardCopyOption.REPLACE_EXISTING`

### 2.5 `ProcessRunner` — Debug 标记虽声称已移除但仍有残留

- **描述**: README 声称"Clean output — Debug markers (`[REG]`, `[UTIL]`, `[EXPR]`) removed for production use"，但代码中仍有残留的 Logger.debug 调用（如第 772、1161、1379 行等）。
- **影响**: 日志文件可能膨胀

### 2.6 `Main.java` — `determineVfsRoot()` 中未使用的 `init.json` 引用

- **描述**: Javadoc 注释说"尝试从 init.json 读取已配置的根路径"，但方法实现中从未读取 init.json，直接使用了 `cilexec_root`。
- **影响**: 路径配置不可用于覆盖根目录

### 2.7 `ProcessRunner.handleExec()` — 阻塞执行在主线程中

- **描述**: `exec()` 会创建一个新的进程文件并启动一个新的 ProcessRunner 线程，但父进程的 `handleExec()` 在文件操作后重新加载文件，没有等待子进程的机制。这个行为与"exec 会替换当前进程"的常见语义不同。
- **影响**: 语义混淆

---

## 3. 低级别问题 / 代码异味

### 3.1 `pom.xml` 中的 Java 版本

```xml
<maven.compiler.source>26</maven.compiler.source>
```
- **问题**: 目标 Java 26 是极为新的版本（2025+），可能多数开发环境没有安装相应 JDK。通常建议使用 LTS 版本（17 或 21）。
- **影响**: 可能无法在标准开发环境中构建

### 3.2 `PathUtil.normalizePath()` — 白名单校验过于严格

- **位置**: 第 114-118 行
- **描述**: `isValidPathComponent()` 使用正则 `^[\\w.\\- \\p{L}]+$` 校验路径组件。这允许空格和中文，但拒绝了一些合法的文件名字符（如 `+`、`@`、`~` 等）。
- **影响**: 某些文件名无法在 VFS 中创建

### 3.3 `ProcessRunner.java` — 多处 `@SuppressWarnings("unchecked")` 被滥用

- **描述**: 整个文件中，几乎所有方法都标记了 `@SuppressWarnings("unchecked")`，实际只有少数几处需要。这掩盖了真正的类型安全问题。
- **影响**: 难以发现真正的类型安全漏洞

### 3.4 `Constants.java` — 注释为英文但代码注释为中文

- **描述**: 文件注释风格不统一，部分常量注释用英文如 `"Owner"`、`"Others"`，部分用中文。整体风格混杂。

### 3.5 `UserUtil.getListOfUsers()` 返回 Map 但被 `.toString()` 使用

- **文件**: `UserFunctionProvider.java` 第 357 行
```java
return UserUtil.getListOfUsers().toString();
```
- **问题**: `Map.toString()` 的输出格式不可控，可能包含大量无关信息。

### 3.6 `PrivilegedFunctionProvider` 中 `system.exec` 的跨平台问题

- **描述**: `Runtime.getRuntime().exec(new String[]{"bash", "-c", cmd})` 硬编码了 `bash`，在 Windows 上会失败。
- **影响**: 跨平台兼容性问题

### 3.7 `Lexer.java` — 负数字面量处理逻辑分散

- **描述**: Lexer 中既在 `switch/case '-'` 分支处理负数，又在 `readNumber()` 中处理前导 `-`。Parser 的 `parseUnary()` 也处理一元负号。三层处理逻辑容易冲突。
- **影响**: `-(5+3)` 这样的表达式可能被错误解析

### 3.8 `INIT.fcl` — 第 737-740 行的死循环兜底代码永远不会被执行

```fcl
// ── 进程保持存活（永不抵达这里，但保留作为安全兜底）──
while (true) {
    // shell 循环已通过 break 退出
}
```
- **描述**: 注释说"永不抵达这里"，确实如此 —— 前面的 break 已经退出了主循环，进程的 `run()` 会因 `currentLine >= codeLines.size()` 而结束。

---

## 4. 安全漏洞

### 4.1 `PrivilegedFunctionProvider.system.exec()` — 宿主机命令执行

- **文件**: `PrivilegedFunctionProvider.java`
- **严重性**: 🔴 **严重**
- **描述**: 允许经过身份验证的 local 用户在宿主机上执行任意 bash 命令。如果 CilExec 被集成到其他服务中，攻击者一旦获得 local 用户凭证，就可以在宿主机上执行任意代码。
- **攻击向量**: 任何可以调用 `system.exec("rm -rf /")` 的 FCL 脚本
- **缓解**: 仅用于教学演示，但仍应增加确认步骤或白名单

### 4.2 `SocketUtil.socketBind()` — 无端口绑定限制

- **文件**: `SocketUtil.java`
- **严重性**: 🟡 **中**
- **描述**: 任何 local 用户可以在任意端口上绑定监听，可能导致端口劫持或未授权的网络服务。

### 4.3 `PrivilegedFunctionProvider.system.invoke()` — Java 反射调用

- **文件**: `PrivilegedFunctionProvider.java`
- **严重性**: 🔴 **严重**
- **描述**: 允许通过 Java 反射调用任意类的任意静态方法。这完全绕过了 VFS 文件系统的权限模型。例如可以调用 `System.exit(0)` 或 `Runtime.getRuntime().exec()`。
- **缓解**: 仅用于教学演示，生产环境应移除

### 4.4 密码以明文存储

- **描述**: 用户密码在 `users.json` 中以明文存储，没有做任何哈希处理。这是教学系统的常见问题，但仍应注明。

---

## 5. 架构设计问题

### 5.1 磁盘 I/O 密集导致的性能瓶颈

- **描述**: 每个进程的每次 tick（10ms）都会做一次 `loadFromFile()` + `saveToFile()`，即完整的磁盘序列化/反序列化。对于 N 个进程，每秒就有 100×N 次文件读写。虽然设计哲学就是"磁盘为主"，但 10ms tick 的密集 I/O 会导致严重的性能下降。
- **建议**: 引入内存缓存层，每 5-10 个 tick 才同步一次到磁盘

### 5.2 进程文件格式使用 JSON + 元数据头格式

- **描述**: 进程文件同时包含元数据头（`#<META>...<META>#`）和 JSON 正文。这种自定义格式的优点是可以直接查看，但每次读写都需要解析元数据头 + JSON 内容，额外开销大。
- **建议**: 考虑将元数据与进程数据分离，或使用更高效的序列化格式

### 5.3 全局共享的 `waitLock` 对象

- **文件**: `ProcessRunner.java` 第 81 行
```java
private static final Object waitLock = new Object();
```
- **问题**: 所有进程共享同一个 `waitLock` 对象。当进程终止时，`waitLock.notifyAll()` 会唤醒**所有**正在等待的进程，而不仅仅是等待该特定子进程的父进程。
- **影响**: 虚假唤醒，CPU 浪费

### 5.4 异常处理体系未发挥作用

- **描述**: 定义了完善的异常层次结构（`ProcessException` → `RecoverableException` / `UnrecoverableException`），但实际使用中大部分异常仍然是通过 `return new String[]{Constants.ERROR_MARKER, message}` 的方式返回，而不是通过异常机制传播。`ExceptionContext` 中记录的 PID、行号等上下文信息也几乎没有被设置。

### 5.5 函数名匹配逻辑过于复杂

- **描述**: `FunctionRegistry.call()` 中的匹配逻辑有三遍遍历：
  1. 有命名空间 → 精确匹配
  2. 无命名空间 → 先匹配空命名空间 provider
  3. 无命名空间 → 再匹配非空命名空间 provider 的短名
  
  这导致函数名解析的行为难以预测。例如 `read()` 有可能匹配到不同 Provider 中的同名函数。

---

## 6. 缺失功能 / 未实现

### 6.1 `PAUSE/CONTINUE` 标记有定义但无实现

- **描述**: `ProcessFunctionProvider` 可以返回 `"PAUSE:pid"` 和 `"CONTINUE:pid"` 标记，但 `ProcessRunner.handleSpecialMarker()` 中对应的处理方法是 **空的**：
```java
} else if (marker.startsWith("PAUSE:")) {
    handlePause(marker.substring(6));
```
但 `handlePause()` 和 `handleContinue()` 方法不存在或为空。

### 6.2 单引号字符串支持不一致

- **描述**: `Lexer` 不支持单引号字符串，但 `countBracesInLine()` 和 `expandInlineBraces()` 考虑了单引号。用户无法使用 `'hello'` 这样的字符串字面量。

### 6.3 没有单元测试

- **描述**: `src/test/java/com/follarce/` 目录为空，项目中没有任何 JUnit 或其他测试框架的依赖。这是一个约 7500 行代码的教学项目，没有自动化测试覆盖。

### 6.4 没有并发控制或文件锁的超时机制

- **描述**: 文件锁定机制 (`FileUtil.lock/unlock`) 没有超时机制。如果持有锁的进程崩溃而不能释放锁，锁会永久存在（虽然有 `checkAndValidateLock` 可以检测崩溃进程的锁，但它只在特定路径调用）。

---

## 7. 改进建议

### 7.1 高优先级

1. **移除 `clearTransientState()` 的调用注释** — 在 `run()` 循环中添加调用
2. **删除 Debug 数据泄露代码** — 第 771-773 行
3. **修复 `NetworkUtil` 的 HTTP 错误处理** — 使用 `getErrorStream()`
4. **添加 `ATOMIC_MOVE` fallback** — 处理 `AtomicMoveNotSupportedException`

### 7.2 中优先级

5. **降低 Java 版本要求** — 改为 Java 17 或 21 LTS
6. **合并 `IOFunctionProvider` 和 `UtilFunctionProvider`** — 消除功能重复
7. **实现 `PAUSE/CONTINUE` 功能** — 或移除对应的标记定义
8. **添加单元测试依赖到 `pom.xml`** — JUnit 5 + Mockito
9. **修复 `waitLock` 的设计** — 改为每个进程独立的条件变量

### 7.3 低优先级

10. **统一代码风格** — 中英文注释混用、缩进风格等
11. **移除 `PrivilegedFunctionProvider` 中的危险功能** — 或增加防火墙
12. **密码哈希** — 使用 `java.security.MessageDigest` 或 BCrypt
13. **添加更详细的 Javadoc** — 特别是对 FCL 脚本语法的文档

---

## 8. 总结

### 项目评价

CilExec 是一个**有雄心且实现完整的教学模拟操作系统**。整个代码库约 **7500 行 Java** + **740 行 FCL Shell**，涵盖：

- FCL 脚本引擎（词法分析 → 语法分析 → AST 求值）
- 虚拟文件系统（带元数据、权限、文件锁定）
- 多进程调度（目录扫描 + 每进程独立线程）
- 交换池 IPC 机制
- 网络/Socket 通信
- 用户系统和权限模型

### 代码质量

| 维度 | 评分 | 说明 |
|------|------|------|
| 架构设计 | ★★★★☆ | 整体架构清晰，模块划分合理 |
| 代码可读性 | ★★★☆☆ | Javadoc 基本完整，但中英文混杂 |
| 错误处理 | ★★☆☆☆ | 异常体系设计了但未被充分利用 |
| 性能 | ★★☆☆☆ | 10ms 磁盘 I/O 过于密集 |
| 安全 | ★☆☆☆☆ | 未做基本的安全防护（明文密码、反射调用） |
| 测试覆盖 | ☆☆☆☆☆ | 完全没有自动化测试 |
| 并发安全 | ★★☆☆☆ | 存在多个竞态条件和共享状态问题 |

### 已发现的 Bug 统计

| 级别 | 数量 |
|------|------|
| 🔴 严重 | 7 |
| 🟡 中等 | 7 |
| 🟢 低级别 | 8 |
| 🔒 安全 | 4 |
| 🏗️ 架构 | 5 |
| 总计 | 31 |

> **注意**: 本项目完全由 AI 生成（根据 README 说明），以上 Bug 分析应结合项目的"教学演示"定位来解读。部分"Bug"可能是有意为之的设计权衡。

---

## 附录: 快速 Bug 速查表

```
ID  文件               行号    级别  描述
─── ───────────────── ────── ──── ─────────────────────────────
B01 ProcessRunner     1548    🔴   clearTransientState() 永不调用
B02 ProcessRunner     771-773 🔴   Debug 日志泄露所有变量数据
B03 Scheduler         53-75   🔴   可能重复启动 INIT 进程
B04 ProcessRunner     1576    🔴   子进程退出不清理父 Child 列表
B05 NetworkUtil       20,45   🔴   4xx/5xx 响应错误处理
B06 ProcessRunner     -       🟡   lastForkChildPid 竞态
B07 ProcessRunner     -       🟡   While 循环行号推进缺陷
B08 SwapFunctionProv  703     🟡   死代码残留
B09 IOFunctionUtil    -       🟡   功能重复
B10 CountBraces       1314    🟡   单引号支持不一致
B11 FileUtil          593     🟡   AtomicMove 无 fallback
B12 ProcessRunner     -       🟡   Debug 标记残留
B13 Main              -       🟡   未使用的 init.json 引用
B14 ProcessRunner     -       🟡   exec 语义混淆
S01 PrivilegedFunc    -       🔴   宿主机命令执行漏洞
S02 SocketUtil        -       🟡   无端口绑定限制
S03 PrivilegedFunc    -       🔴   Java 反射调用
S04 UserUtil          -       🟡   密码明文存储
A01 pom.xml           -       🟢   Java 26 编译版本
A02 PathUtil          114     🟢   路径校验过于严格
A03 ProcessRunner     -       🟢   @SuppressWarnings 滥用
A04 Constants         -       🟢   注释风格不统一
A05 UserFuncProv      357     🟢   Map.toString() 输出不可控
A06 PrivFunction      -       🟢   硬编码 bash
A07 Lexer             -       🟢   负数解析逻辑分散
A08 INIT.fcl          737     🟢   死循环兜底代码永不执行
D01 ProcessRunner     81      🏗️   waitLock 全局共享
D02 ExceptionSys      -       🏗️   异常体系未充分利用
D03 FunctionRegistry  -       🏗️   函数匹配逻辑过于复杂
D04 DiskIO            -       🏗️   10ms I/O 密集瓶颈
D05 FileFormat        -       🏗️   进程文件格式效率低
```

---

*本文档由 AI 自动生成，旨在为项目维护提供 Bug 定位和代码改进参考。*
