# 虚拟线程迁移方案

> 本文档描述将 CilExec 从单线程调度器迁移到
> 「每进程一个虚拟线程」模型的设计。

## 动机

当前单线程调度器用做了两级工作：
1. **调度决策** — 决定哪个进程运行、运行多久
2. **进程执行** — 实际执行 FCL 代码

虚拟线程方案把第 2 项交给 JVM，CilExec 只保留第 1 项的「优先级」语义。

## 前提

### Constants.java 新增开关

```java
// ── 执行引擎模式 ──
// true  = 每进程一个虚拟线程（需 Java 21+）
// false = 单线程调度器（当前模式，兼容 Java 11+）
public static final boolean USE_VIRTUAL_THREADS = false;
```

所有分支代码通过此开关控制，两种模式共存。

## 架构变更

### 当前（单线程调度器）

```
Scheduler 线程（单线程循环）:
  scanForNewProcesses()
  checkBlockedProcesses()
  dispatchNext()
    → ProcessRunner.step()
    → ProcessRunner.step()
    → ProcessRunner.step()   同一线程执行 N 行
  Thread.sleep(50ms)

ProcessRunner:
  step() → executeLine() → 状态机
  无自己的线程
```

### 虚拟线程模式

```
Scheduler（只有进程发现生命周期的功能，不再是调度执行）:
  scanForNewProcesses()
    → 发现新 .proc → 创建 ProcessRunner → startVirtualThread()
  checkBlockedProcesses()
    → 进程已由虚拟线程自己管理
  Thread.sleep(50ms)  ← 此时 scheduler 只负责进程发现

ProcessRunner（恢复 Thread 模式，但用虚拟线程）:
  不再继承 Thread，改为 VirtualThreadRunner？
  内部循环:
    loadFromFile()
    executeLine() × 量子行数
    saveToFile()
    Thread.yield() / sleep(0)  ← 让出CPU给其他虚拟线程
```

## 关键设计决策

### 1. 优先级丢失

虚拟线程不支持 `setPriority()`。JVM 的 ForkJoinPool 使用工作窃取调度，所有虚拟线程公平竞争。

**补偿方案：** 控制每个进程每次让出 CPU 前执行的量子大小。高优先级进程量子大（如 20 行），低优先级量子小（如 2 行）。同等条件下，高优先级进程占用 CPU 的比例更高。

但这只是近似，不是精确控制。

### 2. 原子写入竞争

当前单线程下无写入竞争。虚拟线程模式下，每个进程独立写自己的 .proc，不会冲突（不同文件）。但 `cleanParentChildList()` 和 `handleKill()` 会写**父进程的 .proc**，产生写入竞争。

**方案：** 对每个 .proc 文件加文件锁，或用一个中心化 Serializer 串行化所有写入。

### 3. 阻塞等待简化

当前：
```
调度器每 tick 读磁盘检查子进程文件是否存在
```

虚拟线程模式：
```
ProcessRunner.handleWait():
  while (子进程文件存在) {
    LockSupport.parkNanos(50_000_000)  // 50ms
    // 虚拟线程 park 时不占平台线程
  }
```

虚拟线程在 park 时会被 JVM 从载体线程卸载，不消耗 CPU。这比当前方案更优雅。

### 4. 边界表仍然需要

虚拟线程模式不改变「预扫描生成边界表」的设计。`CodeLoader` + `BoundaryTable` 保留。

### 5. 调度器职责变化

| 职责 | 当前 | 虚拟线程模式 |
|------|------|------------|
| 进程发现 | scanForNewProcesses() | 保留 |
| 就绪队列 | 三队列手写 | 不需要（JVM 管理） |
| 阻塞队列 | blockedProcesses + 轮询 | 不需要（park/unpark） |
| 时间片 | dispatchNext() × QUANTUM | 每个虚拟线程循环内自控 |
| 进程终止检测 | scanForNewProcesses 中检查 | 虚拟线程自然结束 |
| 子终止唤醒父 | scan 中检测后 wake blocked | 子终止时显式 LockSupport.unpark(父) |

## 实现步骤

1. Constants.java 添加 `USE_VIRTUAL_THREADS` 常量
2. ProcessRunner 新增 `virtualThreadRun()` 方法（内部循环 + 量子控制）
3. Scheduler 根据开关决定：startVirtualThread() vs dispatchNext()
4. 文件写入加锁（写父进程 .proc 的场景）
5. 测试两种模式行为一致

## 不修改的部分

- CodeLoader / BoundaryTable / ExpressionEvaluator — 与线程模型无关
- .proc 文件格式 — 不变
- StateManager — 仅需原子写入增强（writeAtomic），与线程模型无关
