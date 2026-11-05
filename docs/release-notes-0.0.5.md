# CilExec 0.0.5

CilExec 0.0.5 提供基于 PostgreSQL 的可恢复 FCL Runtime。进程、续体、调度、IPC、
定时器、VFS 和外部效果在执行切片提交后可恢复。

## 本次更新

- 新增 `volatile func`：纯计算在内存中完成，函数局部状态不写入数据库；移除 `process.run`。
- 新增 `persistent func`：函数调用栈中的每个解释器步骤都提交恢复检查点。
- editor 的完整画面渲染改用非持久函数，贪吃蛇的纯文本排版也使用非持久函数。
- 网络请求与 `io.print/println` 在切片提交后由效果 worker 执行；`term.render`
  在提交后发布画面。

## 下载

| 文件 | 平台与架构 |
| --- | --- |
| `cilexec-0.0.5-linux.jar` | Linux x86-64、ARM64 |
| `cilexec-0.0.5-windows.jar` | Windows x86-64 |
| `cilexec-0.0.5-macos.jar` | macOS x86-64、Apple Silicon |
| `cilexec-0.0.5-general.jar` | 包含上述所有平台资源 |

四个 JAR 的 Runtime 代码相同，区别是内置 PostgreSQL 的平台资源。需要 Java 26。
在专用目录中运行：

```bash
java -jar cilexec-0.0.5-general.jar
```

首次运行会在 JAR 所在位置创建 `cilexec-data/`。使用已有数据前请备份。
0.0.5 的数据格式与 0.0.4 及更早版本不兼容，旧数据不会自动升级；请为
0.0.5 使用新的数据目录或数据库。四个 0.0.5 平台 JAR 使用相同的数据格式。

下载后使用 `SHA256SUMS` 校验文件。详细用法和构建说明见仓库的
`README.md` 与 `docs/release.md`。
