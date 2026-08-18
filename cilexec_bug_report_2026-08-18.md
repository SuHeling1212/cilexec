# CilExec Bug 扫描报告

日期：2026-08-18  
仓库：SuHeling1212/cilexec  
范围：全仓库静态检查，不只看最近提交  
原则：只汇报，不修改代码、不创建 Issue/PR/分支/提交

---

## 1. 高严重度：Effect 的 `PREPARED` 超时回收 SQL 与数据库约束冲突

**严重程度：高**  
**置信度：极高**

### 位置

- `src/main/java/com/follarce/persistence/postgres/repository/JdbcEffectRepository.java`
- `src/main/java/com/follarce/app/DeliverySweeper.java`
- `src/main/resources/db/baseline/effect_terminal_audit.sql`

### 问题

`JdbcEffectRepository.reclaimStalePrepared()` 会把超过一定时间的 effect 从 `PREPARED` 直接更新为 `FAILED`：

```sql
UPDATE effect.effect
SET status='FAILED',
    failure_code=?,
    failure_message=?,
    updated_at=?
```

但是正常的 `PREPARED` effect 中：

```text
claimed_by = NULL
claimed_at = NULL
executing_at = NULL
```

而数据库 CHECK 约束要求 `FAILED` / `UNKNOWN` 状态必须满足：

```text
claimed_by IS NOT NULL
claimed_at IS NOT NULL
executing_at IS NOT NULL
failure_code IS NOT NULL
failure_message IS NOT NULL
```

因此：

```text
PREPARED
→ reclaimStalePrepared()
→ UPDATE PREPARED → FAILED
→ PostgreSQL CHECK constraint violation
→ sweep transaction 回滚
```

### 触发条件

1. 创建一个 effect；
2. effect 停留在 `PREPARED` 超过 1 分钟；
3. `DeliverySweeper.sweepOnce()` 运行；
4. 调用 `reclaimStalePrepared()`；
5. UPDATE 被数据库 CHECK 约束拒绝。

### 影响

`TimerLoop` 会继续执行维护循环，因此这个坏掉的 PREPARED effect 仍然存在，后续维护周期可能反复撞到同一个数据库异常。

可能表现为：

- maintenance loop 持续失败；
- 日志反复出现数据库异常；
- stale PREPARED effect 无法被回收；
- 其他维护操作可能被同一事务一起回滚。

### 测试漏洞

`DeliverySweeperTest` 使用 fake repository，并直接构造了：

```text
claim()
→ start()
→ fail()
```

之后的合法 `FAILED` effect 来模拟“reclaimed PREPARED”。

因此测试没有真正执行生产环境中的：

```text
PREPARED → FAILED
```

数据库状态转换，也就没有触发 CHECK constraint。

---

## 2. 中严重度：`openOrResume()` 存在并发创建两个 HOST Terminal Session 的竞态

**严重程度：中**  
**置信度：高**

### 位置

- `src/main/java/com/follarce/terminal/TerminalService.java`
- `src/main/java/com/follarce/persistence/postgres/repository/JdbcTerminalRepository.java`
- `src/main/resources/db/baseline/effect_terminal_audit.sql`

### 问题

`TerminalService.openOrResume(ownerId)` 使用 `READ_COMMITTED`：

```text
findOpenSession(ownerId)
→ 如果不存在
→ 创建随机 UUID
→ saveSession()
```

而 `findOpenSession()` 只是普通 SELECT，没有 `FOR UPDATE`。

数据库中也没有类似：

```text
UNIQUE(owner_id) WHERE status='OPEN' AND terminal_type='HOST'
```

这样的约束。

因此两个并发请求可能发生：

```text
请求 A：findOpenSession → 空
请求 B：findOpenSession → 空

A：创建 session UUID-A → 成功
B：创建 session UUID-B → 成功
```

最后同一个用户拥有两个 OPEN HOST session。

### 影响

后续 `findOpenSession()` 只通过：

```sql
ORDER BY last_activity_at DESC, session_id
LIMIT 1
```

选择其中一个。

可能导致：

- 用户的 REPL 上下文发生漂移；
- 不同连接连接到了不同 durable terminal session；
- session 状态、history、attachment 行为变得不确定。

---

## 3. 中严重度：Market 的 `publish()` 并不满足其声明的原子发布语义

**严重程度：中**  
**置信度：高**

### 位置

- `market-server/src/main/java/com/follarce/market/server/MarketRepository.java`

### 问题

`MarketRepository.publish()` 注释声称：

> failed publication leaves both the repository and the catalog untouched.

但实际执行顺序是：

```text
1. Files.copy(staged.source(), target)
2. 修改 catalog 数据
3. writeCatalogVerified()
4. refresh()
```

也就是说 package 文件先写入，catalog 后提交。

如果发生：

```text
Files.copy()              成功
writeCatalogVerified()    失败
```

就会留下：

```text
catalog：没有这个包
package file：已经存在
```

这与函数注释声明的原子性不一致。

### 更严重的后续影响

之后如果再次发布同一个 coordinate，但内容不同：

```text
foo/bar/1.0.0
```

由于上一次失败已经留下旧文件：

```text
packages/foo/bar/1.0.0/bar.db
```

代码会检测到：

```text
target 已存在
SHA-256 不同
```

然后抛出：

```text
Different package content already published
```

于是一次从未真正成功完成的发布，可能永久阻挡这个 coordinate 的另一份内容发布。

---

# 本轮排除的疑点

## `JdbcTimerRepository.save()` 不更新 `wake_at/payload`

一开始看起来很可疑：

`ON CONFLICT (timer_id)` 时不会更新：

- `wake_at`
- `payload`

但继续检查调用路径发现：

`TimerService.schedule()` 每次都会生成新的随机 `timerId`。

当前代码没有使用同一个 `timerId` 重排 timer 的正常路径。

因此本轮不把它算作 bug。

---

## Ctrl+C 对阻塞进程是否可能失效

单看 scheduler：

interrupt worker 只 claim：

```text
status='READY'
```

因此最初看起来：

```text
WAITING_INPUT
WAITING_TIMER
WAITING_EFFECT
WAITING_IPC
```

可能永远无法被 Ctrl+C 中断。

但继续检查 `TerminalService.interrupt()` 后确认：

阻塞进程会先：

```text
清除 wait
→ 状态改成 READY
→ 重新 enqueue
```

然后再唤醒 interrupt worker。

因此这条路径目前是正确的，本轮不报 bug。

---

# 本轮结论

本轮确认/高置信度发现：

1. **Effect PREPARED 超时回收与 PostgreSQL CHECK constraint 冲突**  
   严重程度：高  
   置信度：极高

2. **Terminal `openOrResume()` 存在重复 OPEN HOST session 并发竞态**  
   严重程度：中  
   置信度：高

3. **Market `publish()` 失败后可能遗留幽灵 package 文件，破坏原子发布语义**  
   严重程度：中  
   置信度：高

本轮未修改任何代码，也未创建 Issue、PR、分支或提交。
