# 发布 CilExec

## 本地一键发布

在项目根目录执行：

```bash
./build/release.sh
```

Windows 执行：

```bat
build\release.bat
```

默认流程会运行全部 Maven 测试并生成：

- `dist/cilexec-app.jar`
- `dist/cilexec-market-server.jar`
- `dist/repository/packages/<namespace>/<name>/<version>/<name>.db`
- `dist/catalog.json`
- `dist/SHA256SUMS`

流程在临时目录中完成 JAR、SQLite 包、市场清单和 SHA-256 的交叉验证。只有全部验证
通过后才替换现有发布物。Git 提交号会写入 Runtime；不在 Git 工作树中构建时，必须
通过 `CILEXEC_BUILD_REVISION` 提供提交号。本地工作树含未提交改动时，自动记录为
`<commit>-dirty`，避免把不可复现的本地构建误认为该提交的正式成品。

可选参数：

```bash
# CI 已先执行完整测试时使用
./build/release.sh --skip-tests

# 不构建，只复核 dist 中已有的全部发布物
./build/release.sh --verify-only
```

`--skip-tests` 只适用于同一次可信 CI 任务已经完成测试的情况，不应作为人工正式发布的
默认选项。

## GitHub Actions

普通 push 和 pull request 会运行 Java、市场服务端、宿主脚本、Docker 镜像以及完整发布
目录验证。推送 `v*` 标签或手动运行 `release-artifacts` workflow 会执行完整发布流程，
并上传 `cilexec-release.tar.gz`。归档中包含两个 JAR、市场仓库、清单、说明和校验文件，
不包含源码目录或构建缓存。

下载归档后先在解压目录验证：

```bash
sha256sum -c SHA256SUMS
```

macOS 可使用：

```bash
shasum -a 256 -c SHA256SUMS
```
