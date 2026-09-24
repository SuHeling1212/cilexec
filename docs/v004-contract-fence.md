# CilExec 0.0.4 / V004 Contract Fence

This document describes the current, unreleased 0.0.4 source state. It does not announce or
publish a release.

## Scope

V004 is the still-unreleased 0.0.4 contract-completion migration. It grants `DELETE` on the
forced-RLS `process.package_binding` table to the Runtime and to existing tenant roles, and wraps
the tenant-role provisioning function so future users receive the same privilege. This lets
`process.exec` discard only the calling owner's old package pins in the same transaction as the
replacement continuation. V004 then checks that the V003 administrator functions, explicit
audit-retention policy, compiled-program column, complete process wait-kind constraint, and
database security invariants are intact. It performs no application-table DDL or user-data
rewrite. Flyway records schema version 4 only when an operator explicitly runs the normal
migration workflow.

The Java migration uses a deterministic checksum derived from its resource identity and normalized
SQL bytes. V004 may still change while 0.0.4 remains unreleased; after release its checksum becomes
part of the immutable migration history. Tests also pin the complete V002 and V003 Java source
hashes, so accidental edits to either historical migration fail the build even though those older
migrations did not expose a Flyway checksum.

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

V004 是尚未冻结的 0.0.4 契约补全迁移：它为 Runtime、现有租户角色以及后续新建租户角色补上
forced-RLS 表 `process.package_binding` 的 `DELETE` 权限，使 `process.exec` 能在提交替换 continuation
的同一事务中清除调用者自己的旧包绑定；随后再校验 V003 留下的数据库契约与安全不变量。它不执行
应用表 DDL，也不改写用户数据。只有运维人员显式运行迁移流程时，Flyway 才会记录 Schema 版本 4。
0.0.4 新建的程序和 continuation 使用格式 4，同时继续读取 V002 JSON 程序、V002 continuation、
V003 FCLB 程序和 V003 continuation。包语言版本 `fcl-0.0.2` 与 `fcl-0.0.3` 仍可导入，新包应声明
`fcl-0.0.4`。0.0.4 发布后，V004 的校验和才成为不可变迁移历史的一部分。

实际升级前必须先创建并验证逻辑备份。0.0.4 Runtime 只接受 Schema 4；不支持自动降级，回滚方式是
恢复升级前备份。单纯构建或测试源码不会迁移持久数据库、替换正在运行的 Runtime 镜像或发布产物。
