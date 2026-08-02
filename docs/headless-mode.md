# 无头模式

无头模式让宿主终端直接执行一段 FCL，不进入 CilExec 的登录菜单和交互式 Shell。它仍然
连接已经运行的共享 Runtime JVM，因此不会为一次调用启动 JVM，也不会为每条指令创建新的
FCL 进程。

首次安装和创建管理员账户仍然使用：

```bash
./Install.sh
```

之后可在同一个宿主终端连续执行：

```bash
./Headless.sh 'counter = 1'
./Headless.sh 'counter = counter + 1; io.println(counter)'
```

第二条指令输出 `2`。同一个宿主终端的调用会复用同一个持久 REPL session，以及其中暂停的
FCL 进程、变量、函数、导入和当前工作目录。另开一个宿主终端会得到独立上下文。

脚本通过宿主 TTY 路径的 SHA-256 摘要生成上下文 ID，不把 TTY 路径发送给 CilExec。用户名
默认是 `local`，可通过 `CILEXEC_TERMINAL_USERNAME` 选择其他用户。密码由无回显提示读取，
通过标准输入和容器内 loopback socket 发送；密码不会出现在命令行参数或环境变量里。

CI 或没有 TTY 的场景必须显式指定稳定、非敏感的上下文 ID：

```bash
CILEXEC_HEADLESS_CONTEXT=build-42 ./Headless.sh 'io.println("done")'
```

不同上下文 ID 不共享变量。不要把上下文 ID 当作认证凭据；每次调用仍然必须提供 CilExec
用户密码。无头输入最多为 4 MiB，防止单个 socket 请求耗尽 Runtime 内存。

无 TTY 的自动化环境可以从受保护的标准输入提供一行密码；密码后不要追加其他内容，因为
FCL 源码已经由脚本参数提供：

```bash
printf '%s\n' "$SECRET_FROM_SAFE_STORE" | \
  CILEXEC_HEADLESS_CONTEXT=build-42 ./Headless.sh 'io.println("done")'
```
