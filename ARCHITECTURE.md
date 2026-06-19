# CilExec 架构重构设计

> 本文档用于交接给新对话。当前上下文已因长度产生幻觉/不一致，
> 所有实现决策以本文档为准。

## 当前状态

### 已完成的改动

1. **调度器重构** — ProcessRunner 从 Thread 改为状态机，Scheduler 实现三级优先级轮转
2. **wait 非阻塞化** — 不再阻塞线程，调度器定期检查唤醒
3. **子进程终止直接唤醒父进程** — scanForNewProcesses 中检测到子进程文件消失时直接唤醒父进程
4. **INIT 优先级 = LOW**（ProcessInit.java 写 Priority 字段）
5. **进程文件后缀已改为 .proc**（PathUtil.PROCESS_EXT + 各文件硬编码引用已改）

### 待处理的 bug

1. **expandInlineBraces 把 map 字面量拆碎了**
   - 详见 BUG_INLINE_BRACES.md
   - 按下方「目标架构」整体重构后此 bug 自然消失，不应单独修

## 目标架构

### 核心理念

**磁盘是唯一真相，内存只是瞬态暂存。**

原始 FCL 源码 → .fcl 文件 → .proc 进程文件（完整状态）→ 调度器每 tick 驱动 → 执行一行写回

### .proc 进程文件格式

```json
{
  "PID": 1,
  "Name": "INIT",
  "Owner": "local",
  "Priority": 1,
  "Status": true,
  "RunningTime": 42,
  "Parent": {},
  "Child": {},
  "Path": "/system/config/INIT.fcl",
  "Code": [
    "print(\"start\")",
    "x = 1",
    "while x < 5",
    "{",
    "  x = x + 1",
    "}",
    "print(\"done\")"
  ],
  "Data": { "x": 3 },
  "runningCodeLine": 6,
  "BlockStack": [
    { "type": "WHILE", "bodyStart": 3, "bodyEnd": 5, "condition": "x < 5" }
  ]
}
```

原则：
- **Code 数组** = 原始代码行。**注释已在加载时剔除**，不要在运行时跳过注释行
- **Data** = 进程变量，唯一需要持久化的运行状态
- **runningCodeLine** = 当前执行行号
- **BlockStack** = 正在执行中的控制流边界。必须持久化（断电恢复需要）。
  运行完的块必须弹出：if body 结束 → 弹出；while 条件不满足 → 弹出
- **不落盘的瞬态字段**：returnValue、CallStack、pendingAssignVarName、importedFiles
  saveToFile 不写入这些字段

### 执行流程

**预扫描（每次加载代码时做一次）：**
```
遍历 Code 行：
  遇到 if / while → 花括号匹配找到 body 范围
  记录边界表: { type, condLine, bodyStart, bodyEnd }
```

**code → pre-scan → boundary table → ProcessRunner：**
```
加载代码 → 剔除注释 → 扫描生成边界表 → 开始执行

每 tick:
  读 .proc → 重建边界表 → 查表执行一行 → 修改 Data → 写回 .proc → 清内存瞬态
```

**控制流处理：**
- if true → BlockStack push → runningCodeLine 跳到 bodyStart
- if false → runningCodeLine 跳到 bodyEnd+1
- while true → BlockStack push → runningCodeLine = bodyStart
- while false → BlockStack 弹出 → runningCodeLine = bodyEnd+1
- } → 查 BlockStack 栈顶：
  - IF → 弹出，runningCodeLine = bodyEnd+1
  - WHILE → 判断条件，满足则回到 condLine，不满足则弹出
- break → 找到最近的 WHILE，弹出它及上层所有 → runningCodeLine = whileEnd+1

### 花括号与行

- `{` 和 `}` 应当独占一行（沿用当前约定）
- **删除 expandInlineBraces**：不再需要行内花括号展开
- 边界表通过行号匹配花括号，不依赖字符级括号计数
- map 字面量的 `{` 不会进入边界表（只有 if/while 的才进），不会误伤

### 注意事项

- 函数定义 `func add(a,b) { ... }`：预扫描时识别其边界，跳过函数体（只注册，不执行）
- 嵌套 if/while 由 BlockStack 自然处理（先进后出）

## 补充

### 当前 head 提交

```
db35f3a checkpoint: refactor scheduler with priority-based round-robin
```

### 尚未提交的本地变更

- src/main/resources/INIT.fcl — 被改写过（测试脚本），内容脏了
- BUG_INLINE_BRACES.md — bug 文档
- ProcessRunner.java — 有 expandInlineBraces 修复代码但打包未生效
- ProcessInit.java — INIT 优先级已改为 LOW
- 部分 .pres → .proc 的后缀改动

建议新对话前 git checkout . 清掉工作区，然后按本文档重来。
