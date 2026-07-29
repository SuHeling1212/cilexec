# 宿主机文件复制到 CilExec VFS

`HostMove.sh` 是宿主机侧的单指令导入程序。它隐藏 Docker 工具容器和数据库连接细节，
最终结果是一个真实的 CilExec VFS 文件节点及其数据库对象，不是留在容器文件系统中的
临时文件。

## 使用

在宿主机项目目录执行。脚本会按需生成内部数据库密钥、构建缺失的 CilExec 镜像，并在
PostgreSQL 已停止时自动启动，并在每次导入前校验和迁移数据库：

```bash
./HostMove.sh /absolute/host/report.pdf /documents/report.pdf
```

默认目标用户为 `local`。指定其他用户：

```bash
./HostMove.sh /absolute/host/data.bin /imports/data.bin alice
```

目标 VFS 父目录必须已经存在，目标文件不能已经存在。源文件必须是普通文件，符号链接会
被拒绝。单文件上限与当前 VFS 契约一致，为 1 GiB。

## 持久化与失败行为

1. 脚本把唯一的源文件只读挂载到一次性 Docker 工具容器。
2. Java 导入器以 4 MiB 为单位，把内容作为不可变对象写入 PostgreSQL。
3. 全部字节写完后，在最终数据库事务中创建 VFS 节点并记录审计事件。
4. 工具容器成功退出后，宿主源文件仍然保留；VFS 中保存独立的数据库副本。

任何读取、权限、路径或数据库错误都会令命令失败，并且不会修改宿主源文件。

内部 Java 命令是：

```text
cilexec host move <container-source-file> <absolute-vfs-path> [username]
```

该内部命令供 `HostMove.sh` 使用；其中 `move` 是为兼容现有命令保留的内部名称，实际
行为是复制。它不需要向数据库暴露宿主端口，也不会挂载 Docker Socket。
