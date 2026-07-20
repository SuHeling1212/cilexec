# demo-greeter 示例包

这是 Cilexec 包管理器的完整格式夹具。`packageTEST/` 中的内容可以直接作为
`.pack` 归档的根目录，不需要再套一层目录。

## 目录结构

```text
packageTEST/
├── manifest.json                 # 唯一的包清单与依赖声明
├── payload/                      # 可执行 FCL 代码
│   ├── main.fcl                  # 包入口和公开函数
│   └── internal/
│       └── format.fcl            # 包内部实现
├── resources/                    # 只读非代码资源
│   ├── defaults.json
│   └── messages/
│       └── zh-CN.json
├── hooks/                        # 仅由包管理器执行的生命周期脚本
│   ├── pre-install.fcl
│   ├── post-install.fcl
│   ├── pre-uninstall.fcl
│   └── post-uninstall.fcl
├── docs/
│   └── manifest.schema.json      # manifest v1 的 JSON Schema
├── LICENSE
└── README.md
```

## Manifest 规则

- `schemaVersion` 用于未来格式升级。
- `namespace + name + version` 组成包坐标。
- `version` 必须是精确版本，不允许 `latest`、`^1.0` 或 `>=1.0`。
- `entry`、export 的 `module` 和 resource 路径都相对于包根目录。
- `exports` 明确区分公开函数和 `payload/internal/` 中的内部函数。
- `dependencies` 是唯一依赖来源，不再额外放置 `dependencies/*.link`。
- 包内不保存自身哈希，避免修改 hash 字段后包内容再次变化。
- `lifecycle` 是生命周期脚本的唯一声明，不根据文件名自动执行脚本。

当包需要直接依赖时，使用以下结构：

```json
{
  "binding": "unicode",
  "namespace": "follarce",
  "name": "unicode",
  "version": "1.8.0",
  "integrity": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
}
```

其中 `integrity` 是依赖 `.pack` 文件的真实 SHA-256，不允许使用占位符发布。
当前示例没有外部依赖，因此 `dependencies` 是空数组，它仍然是一个完整合法包。

## 生命周期脚本

manifest 可以声明四个生命周期阶段：

```text
安装：验证并暂存 → preInstall → 原子发布 root → postInstall
卸载：preUninstall → 删除 root → postUninstall → 允许 GC
```

- `preInstall` 在包对用户可见前执行；失败会中止安装。
- `postInstall` 在 root 已发布后执行；失败时包保持已安装，事务记录错误并重试。
- `preUninstall` 在删除 root 前执行；失败或返回 `allow: false` 会中止卸载。
- `postUninstall` 在删除 root 后执行；包对象会被事务 pin，脚本完成前不能被 GC。
- 每次执行必须带稳定 `effectId`，崩溃恢复后使用相同 ID 重试。
- hook 继承发起操作的有效用户，不允许包通过 manifest 请求更高权限。
- hook 在受限的轻量 FCL 沙箱中运行，并受 `timeoutMs` 和语句数限制。
- hook 不能执行 package、process、system、network、socket 或 user 操作。
- 允许的本地写操作会获得从安装事务派生的稳定 effect ID。

安装器执行 hook 前应提供只读上下文变量：

```text
__package_event
__package_namespace
__package_name
__package_version
__package_hash
__package_binding
__package_scope
__package_user
__package_data
__effect_id
```

hook 通过 `hookResult` 返回结果：

```fcl
hookResult = {"status": "ok", "allow": true, "message": "ready"}
```

普通 `import 包.*` 不会执行 `hooks/`；只有包管理器可以根据 manifest 调用它们。

已安装包的函数在执行时可以读取自己的数据目录：

```fcl
dataDir = path.getEnvVar("PACKAGE_DATA")
```

该变量只在包函数调用期间有效。数据目录仍采用用户级权限，同一用户安装的包
可以读取和修改其他包的数据；不同用户之间仍由 VFS 权限隔离。

## 打包规则

当前 `PackageBuilder` 会把本目录打成确定性归档：

1. 所有归档路径使用 `/`，并且必须是相对路径。
2. 文件按 UTF-8 路径字典序写入。
3. 统一文件时间戳和权限位。
4. 拒绝绝对路径、`..`、重复文件名和符号链接。
5. 限制文件数量、单文件大小和总解压大小。
6. 对最终 `.pack` 原始字节计算 SHA-256。

包哈希保存在仓库索引和本地对象索引中，不写回 `manifest.json`：

```text
PackageHash = SHA-256(exact .pack bytes)
```

实际存储位置：

```text
/system/app/package/objects/sha256/<前两位>/<完整哈希>.pack
/system/app/data/package/refs/<完整哈希>.json
/system/app/data/package/index.json
/user/<用户>/app/package/installed.json
/user/<用户>/app/data/package/transactions/<事务ID>.json
/user/<用户>/app/data/package/pins.json
/user/<用户>/app/data/package/packages/<namespace>/<name>/
```

对象仓库由所有用户共享并按哈希去重，但每个用户只会看到自己
`app/package/installed.json` 中的安装根及其可达依赖。

## 构建和安装

```bash
mvn package
java -cp target/cilexec-1.0-SNAPSHOT.jar com.follarce.pack.PackageCli \
  build packageTEST /tmp/demo-greeter-1.0.0.pack
java -cp target/cilexec-1.0-SNAPSHOT.jar com.follarce.pack.PackageCli \
  inspect /tmp/demo-greeter-1.0.0.pack
```

在 FCL 中安装后直接按用户绑定导入：

```fcl
result = package.install("/user/alice/app/demo-greeter-1.0.0.pack")
import demo-greeter.*

message = greet("FCL")
println(message)
```

多个版本可安装为不同 binding，并在同一进程中使用独立命名空间：

```fcl
import demo-v1.* as greeter1
import demo-v2.* as greeter2

first = greeter1.greet("FCL")
second = greeter2.greet("FCL")
```

也支持当前用户安装根的绝对写法：

```fcl
import /user/alice/app/package/demo-greeter.*
```

## 目录兼容导入

没有同名已安装绑定时，仍可把本目录复制到 VFS：

```text
/system/app/package/demo-greeter/
```

然后使用目录包导入：

```fcl
import /system/app/package/demo-greeter.*

message = greet("FCL")
println(message)
```

导入器只会递归加载 `payload/` 中的 `.fcl` 文件，并忽略 hooks、manifest、
README、Schema 和资源 JSON。目录兼容导入和 `import 包.*` 都按需求加载全部
payload 文件。`exports` 目前用于验证公开入口确实存在，但不会隐藏未导出的 payload
函数。不带别名的导入仍使用扁平函数表；带 `as` 的导入会隔离根包及其精确依赖图中
的函数，因此可用于同进程多版本。

## 不属于包归档的内容

以下文件由安装器生成，不能放进 `.pack`：

- `refs.json`
- 名称版本索引
- 用户或系统安装 root
- 安装事务日志
- effect receipt
- 缓存和 staging 临时文件
