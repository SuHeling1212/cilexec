# Cilexec Bug 分析与修复计划

---

# 第一部分：用户自定义函数 Bug（✅ 已修复）

## 概述

用户自定义函数（`func` 定义）完全不可用。这不是一个"改一行就完"的 bug，而是 **6 个独立缺陷的叠加效应**，横跨 5 个文件。单独修复任何一个都没用。

## 缺陷矩阵

| # | 文件 | 缺陷 | 影响 |
|---|------|------|------|
| 1 | `FunctionManager.java` | `FUNC_DEF_PATTERN` 正则末尾的 `$` 锚点导致单行函数不匹配 | 单行函数定义不被识别 |
| 2 | `FunctionRegistry.java` | `FunctionManager` 存到本地 `Map`，但 `FunctionRegistry` 查找的是另一个静态 `Map`——两套数据从未联通 | 即使函数被解析，也无法被调用 |
| 3 | `FunctionRegistry.java` | 短名回退遍历在检查用户函数**之前**调用 provider，返回错误拦截 | 无命名空间调用被内置函数拦截 |
| 4 | `FunctionManager.java` | 每次 `step()` → `parseFunctions()` 都执行 `clearUserFunctions()`，进入函数体后 codeLines 被替换，函数定义丢失 | 递归函数报废 |
| 5 | `ProcessRunner.java` | `handleUserFunctionCall` 设置 `currentLine = 0`，但 `dispatchStatement` 末尾无条件 `currentLine++` | 进程进入函数体立刻终止 |
| 6 | `StateManager.java` | `saveToFile` 显式删除了 `CallStack` 且从未写回 | 函数 return 后无法恢复调用者上下文 |

## 各缺陷修复

### 缺陷 1：单行函数定义不被识别

**原始代码**：`Pattern.compile("^func\\s+...\\s*\\{?\\s*$")` — 末尾 `$` 要求行在 `{` 后结束。

**修复**：改为 `\\{?.*$`，增加内联 body 检测（`{ ... }` 在同一行时提取为内联体）。

### 缺陷 2：函数注册和查找两套系统

`FunctionManager.parseFunctions()` 存到实例 `Map`，`FunctionRegistry.call()` 查找静态 `Map`，从未联通。

**修复**：`parseFunctions()` 中调用 `FunctionRegistry.registerUserFunction(name, def)`。

### 缺陷 3：内置函数抢先拦截

无命名空间调用时，provider 短名回退在用户函数检查之前。

**修复**：用户函数检查移到 provider 短名回退之前。

### 缺陷 4：每步执行清空全局注册表

`parseFunctions()` → `clearUserFunctions()` 但进入函数体后 codeLines 不含 func 定义。

**修复**：移除 `parseFunctions()` 中的 `clearUserFunctions()`。

### 缺陷 5：进入函数体后 currentLine 越界

`handleUserFunctionCall` 设 `currentLine = 0`，`dispatchStatement` 末尾无条件 `currentLine++` → 1 >= 1 → 终止。

**修复**：增加 `enteredUserFunction` 标志，跳过 `currentLine++`。

### 缺陷 6：调用栈在持久化时被丢弃

`saveToFile` 删除了 `CallStack` 从未写回。

**修复**：增加 `program.put("CallStack", snapshot.callStackData)`。

### 额外修复：返回值丢失

缺陷 1-6 修完后，`v = dbl(21)` 的返回值没有赋给 `v`。根因：赋值在进入函数体的同一 step 被错误完成。

**修复**：`__pending_assign` 机制 + `completePendingAssignment()`。

## 修复状态

| # | 状态 | 文件 | 变更 |
|---|------|------|------|
| 1 | ✅ | `FunctionManager.java` | 修改 `FUNC_DEF_PATTERN` + 内联 body 提取 |
| 2 | ✅ | `FunctionManager.java` | `parseFunctions()` 调用 `registerUserFunction()` |
| 3 | ✅ | `FunctionRegistry.java` | 用户函数检查移到短名 provider 回退之前 |
| 4 | ✅ | `FunctionManager.java` | 移除 `parseFunctions()` 中的 `clearUserFunctions()` |
| 5 | ✅ | `ProcessRunner.java` | `enteredUserFunction` 标志 |
| 6 | ✅ | `StateManager.java` | `program.put("CallStack", ...)` |
| 7 | ✅ | `ProcessRunner.java` | `__pending_assign` + `completePendingAssignment()` |

## 测试结果

30 项测试全部通过，零错误零警告。非递归用户函数完全正常。递归函数不在设计目标内。

---

# 第二部分：.proc 跨进程写入违规（待修复）

## 核心原则

> **每个 `.proc` 文件的唯一写入者是其所属的 ProcessRunner 对象。**

## 违规 1：`handleKill()` 写 INIT 的 `.proc`（孤儿进程迁移）

**文件**：`IpcHandler.java` 第 247-259 行

```java
// 子进程迁移到 INIT
Map<String, Object> children = (Map<String, Object>) targetData.get("Child");
if (children != null && !children.isEmpty()) {
    String initPath = PathUtil.getProcessFilePath(Constants.PID_INIT);
    String initContent = FileUtil.read(initPath);
    Map<String, Object> initData = JsonUtil.parseToMap(initContent);
    // ...
    initChildren.putAll(children);
    FileUtil.write(initPath, JsonUtil.toMetaJson(initData));        // ← 写入 INIT 的 .proc
}
```

**问题**：INIT（PID 1）是永久运行的进程，有自己的虚拟线程在 `persistState()`。两个虚拟线程同时写同一个 `.proc` 文件。

## 违规 2：`handleKill()` 写父进程的 `.proc`（从 Child 列表移除）

**文件**：`IpcHandler.java` 第 262-274 行

```java
if (parentPid > 0) {
    // ...
    parentChildren.remove(String.valueOf(targetPid));
    FileUtil.write(parentPath, JsonUtil.toMetaJson(parentData));   // ← 写入父进程的 .proc
}
```

**问题**：kill 进程 A 的虚拟线程直接修改父进程的 `.proc`。父进程可能正在正常运行。

## 违规 3：`cleanParentChildList()` 写父进程的 `.proc`（子进程终止清理）

**文件**：`StateManager.java` 第 213-233 行

```java
private void cleanParentChildList() {
    // ...
    ProcessFileLock.lock(ppid);
    try {
        children.remove(String.valueOf(pid));
        FileUtil.write(parentPath, JsonUtil.toMetaJson(parentData)); // ← 写入父进程的 .proc
    } finally { ProcessFileLock.unlock(ppid); }
}
```

**调用链**：`ProcessRunner.step() → running=false → cleanup() → cleanParentChildList()` — 子进程终止时写父进程。

## 调用上下文总览

```
kill 进程 B:
  进程 A 的虚拟线程 → handleKill("2")
    → FileUtil.write(INIT.proc)       ← 违规 1
    → FileUtil.write(parent.proc)     ← 违规 2
    → FileUtil.write(target.proc)     ✅ 可接受

子进程自然终止:
  子进程的虚拟线程 → cleanup()
    → cleanParentChildList()
      → FileUtil.write(parent.proc)   ← 违规 3
```

## 修复方案：Swap Pool 进程间消息传递

不使用跨进程 `.proc` 写入。每个进程通过 **swap pool** 向其他进程发送消息，接收方在自己的 step 循环中自行处理，由**自己的 ProcessRunner** 写入自己的 `.proc`。

```
之前：A → FileUtil.write(B.proc)                                ❌
之后：A → swap.add("_inbox_" + B.pid, msg) → B 自检 → persistState()  ✅
```

### Swap 协议

每个进程有一个收件箱：`_inbox_<pid>`

| 消息类型 | varName | 内容 | 发送方 | 接收方 |
|---------|---------|------|--------|--------|
| 子进程退出 | `child_exit:<childPid>` | 空 | 子进程 | 父进程 |
| 孤儿认领 | `adopt:<killedPid>` | 子进程列表 JSON | kill 进程 | INIT |

### 修复 1：孤儿进程迁移

**删除**：`IpcHandler.java` 第 247-260 行

**新增**：
```
children → swapPool.add("_inbox_1", "adopt:" + targetPid, childrenJson, pid)
ProcessRunner.unparkProcess(1)
```

**INIT 新增**：step 时读 `_inbox_1`，处理 `adopt:` 消息，清理已处理消息，`persistState()`。

### 修复 2 + 3：父进程 Child 列表清理

**删除**：`IpcHandler.java` 第 262-275 行 + `StateManager.java` 第 213-233 行

**新增**：
```
swapPool.add("_inbox_" + ppid, "child_exit:" + pid, "", pid)
ProcessRunner.unparkProcess(ppid)
```

**父进程新增**：step 时读 `_inbox_<pid>`，处理 `child_exit:` 消息，从 Child 中移除，`persistState()`。

### 改动文件

| 文件 | 改动 | 说明 |
|------|------|------|
| `IpcHandler.java` | 删除 line 247-275 | 移除两处跨进程写 |
| `IpcHandler.java` | 新增 swap 发送 | 两处 swap.add() |
| `StateManager.java` | 删除 line 213-233 | 移除 cleanParentChildList 跨进程写 |
| `StateManager.java` | 新增 swap 发送 | swap.add() 通知父进程 |
| `ProcessRunner.java` | 新增 `processInbox()` | 处理收件箱 + 清理 Child |
| `ProcessRunner.java` | executeLine 开头调用 | 每个 step 自检 |

### 不变的部分

以下写入路径不违反单写者原则：

| 路径 | 判定 |
|------|------|
| `saveToFile` 写自己 | ✅ 自己写自己 |
| `handleFork` 写子进程 | ✅ 子进程尚未被调度 |
| `handleKill` 写目标进程 | ✅ 目标即将终止 |
| `handlePause` 写目标进程 | ✅ 目标状态切换中 |
| `handleContinue` 写目标进程 | ✅ 目标状态切换中 |
