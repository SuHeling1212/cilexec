# 真实 FCL 全功能测试

这套测试不是直接调用 Java provider 的模拟测试。每个用例都会：

1. 创建独立、一次性的 `cilexec_root`。
2. 将测试脚本写入 VFS 的 `/system/config/INIT.fcl`。
3. 从 `com.follarce.Main` 启动真实调度器和 PID 1。
4. 由 FCL 调用功能、完成断言并把结果写回 VFS。
5. 检查 PID 1 正常结束且结果中的 `passed` 为 `true`。

## 运行

```bash
bash packageTEST/tests/run-full-fcl-suite.sh
```

也可以只运行一个用例：

```bash
bash packageTEST/tests/run-full-fcl-suite.sh core
bash packageTEST/tests/run-full-fcl-suite.sh package
bash packageTEST/tests/run-full-fcl-suite.sh recovery
```

运行产生的隔离环境保存在 `packageTEST/.runtime/`，该目录已被 Git 忽略，便于失败后检查 `.proc` 和结果文件。

## 覆盖范围

| 用例 | 覆盖内容 |
|---|---|
| `core` | 表达式、集合、索引赋值、分支、循环、函数、递归、导入，以及 math/term/util/path/file/io/user |
| `swap` | 交换池 CRUD、锁、等待和信号 |
| `process` | fork/kill/wait/waitPID/pause/continue、PID/PPID、子进程列表、exit |
| `exec` | 进程代码替换与命名空间导入保持 |
| `socket` | 本机 TCP bind/connect/accept/send/receive/close |
| `network` | 本机 HTTP GET/POST 及兼容别名 |
| `system` | invoke/forceRemove/kill/resolveEffect/exec/ls |
| `input-*` | `util.input`、`io.input`、`io.readChar` |
| `package` | 构建、安装、双版本依赖图、导入、资源、校验、固定、卸载、钩子、恢复，以及进程退出后的实际 GC |
| `recovery` | 执行中强制杀死 JVM，再从同一个 `.proc` 恢复 |
| `reset` | 在一次性 VFS 中真实删除整个根目录 |

`check-fcl-api-coverage.sh` 会在运行前扫描所有 provider，确保每个公开的命名空间函数至少被一个 FCL 脚本调用。网络与 Socket 测试只访问回环地址，不依赖公网。
