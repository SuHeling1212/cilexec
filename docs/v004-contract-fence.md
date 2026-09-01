# CilExec 0.0.4 / V004 Contract Fence

This document describes the current, unreleased 0.0.4 source state. It does not announce or
publish a release.

## Scope

V004 is a validation-only Flyway migration. Its immutable SQL resource checks that the V003
administrator functions, explicit audit-retention policy, compiled-program column, complete
process wait-kind constraint, and database security invariants are intact. It performs no
application DDL, repairs, or user-data rewrite. Flyway records schema version 4 only when an
operator explicitly runs the normal migration workflow.

The Java migration uses a deterministic checksum derived from its resource identity and normalized
SQL bytes. Tests also pin the complete V002 and V003 Java source hashes, so accidental edits to
either historical migration fail the build even though those older migrations did not expose a
Flyway checksum.

## Persisted-format compatibility

New 0.0.4 programs and continuations use format 4. The Runtime also reads:

- V002 JSON program artifacts and V002 continuation envelopes;
- V003 FCLB program artifacts and V003 continuation envelopes.

Package language versions `fcl-0.0.2` and `fcl-0.0.3` remain import-compatible. New packages and
programs should declare `fcl-0.0.4`. Unknown older or future versions are rejected instead of being
guessed compatible.

## Upgrade and rollback boundary

Before applying V004 to a database, create and verify a logical backup. The 0.0.4 Runtime accepts
schema version 4 only. Automatic downgrade is forbidden; rollback after an applied migration means
restoring the pre-upgrade backup. Building or testing this source tree does not migrate a persistent
database, replace a running Runtime image, or publish release artifacts.

## 中文说明

这里记录的是尚未发布的 0.0.4 源码状态，不代表已经发布 V004。

V004 只校验 V003 留下的数据库契约与安全不变量，不执行应用表 DDL、修复或用户数据改写；只有运维
人员显式运行迁移流程时，Flyway 才会记录 Schema 版本 4。0.0.4 新建的程序和 continuation 使用格式
4，同时继续读取 V002 JSON 程序、V002 continuation、V003 FCLB 程序和 V003 continuation。包语言
版本 `fcl-0.0.2` 与 `fcl-0.0.3` 仍可导入，新包应声明 `fcl-0.0.4`。

实际升级前必须先创建并验证逻辑备份。0.0.4 Runtime 只接受 Schema 4；不支持自动降级，回滚方式是
恢复升级前备份。单纯构建或测试源码不会迁移持久数据库、替换正在运行的 Runtime 镜像或发布产物。
