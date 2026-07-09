# 0.2 Clone-System Sync Prechecks

0.2 的目标不是继续做 0.1 的单向“分身 -> 主系统恢复”，而是支持主系统和分身系统两侧都能生成可识别备份，并能在另一侧恢复。

## Product Model

- 主系统 user0 可以读取分身 user10 数据，生成来源为 `user10` 的备份，并恢复到 `user0`。
- 分身系统 user10 可以读取主系统 user0 数据，生成来源为 `user0` 的备份，并恢复到 `user10`。
- 同一包名的备份必须带来源和目标标识，不能只靠目录名判断用途。
- 主动备份、被动备份、切换前备份仍然要分开显示，避免把回滚点误当作可长期保存的手动备份。
- 当任务需要读取分身 CE 数据时，可以通过「分身自动解锁」开关自动触发后台启动和 PIN 验证；开关默认关闭。
- 首页收藏 App 增加「推送」按钮，用于从主系统 `user0` 单向覆盖到分身 `user10`。
- 「推送」不会写入 `switches/<pkg>/active`，不会改变「切换/还原」按钮状态。
- 「推送」前的目标侧备份独立保存到 `/data/adb/uclone/clone_rollback/<pkg>/latest`，每个 App 只保留最新一份，避免和主系统 `rollback` 被动备份重复占屏。
- 数据页显示 `clone_rollback/<pkg>/latest`，可手动恢复到分身 user10；外部协议也提供 `RESTORE_LATEST_CLONE_ROLLBACK`。
- 后续桌面长按 Hook 模块通过受保护外部协议调用 UClone，协议见 `docs/EXTERNAL_ACTION_PROTOCOL.md`。

## Required Record Fields

0.2 的备份 manifest 至少需要补充这些字段：

- `sourceUser`: 数据来源用户，例如 `0` 或 `10`。
- `targetUser`: 默认恢复目标用户，例如 `0` 或 `10`。
- `createdInUser`: 执行 UClone Restore 的当前用户。
- `backupKind`: `manual`、`rollback`、`switch`、`clone_rollback`。
- `activeForUsers`: 这份 active 备份允许作为哪些用户的恢复源。
- `restorableToUsers`: UI 可以显示为可恢复的目标用户。

## Debug Task

诊断页新增「分身系统调试」按钮。这个任务只读，不会启动/关闭用户、切换用户、删除文件或复制数据。
这个任务不受「分身自动解锁」开关影响，始终只读取当前状态。

请先从主系统 user0 运行一次，并把完整日志发回来。日志任务名应为：

```text
TASK=DEBUG_CLONE_SYSTEM
```

需要重点看这些字段：

- `CURRENT_USER`
- `MAIN_STATE`
- `CLONE_STATE`
- `V02_SELF_INSTALLED_IN_CLONE`
- `V02_DEBUG_RAN_FROM_CLONE_USER`
- `V02_THIS_RUNTIME_HAS_ROOT`
- `CLONE_CE_GATE`
- `PATH_MAIN_SELF_CE_*`
- `PATH_CLONE_SELF_CE_*`
- `V02_SNAPSHOT_MANIFEST_JSON`
- `V02_ROLLBACK_MANIFEST_JSON`

## Second Test

如果后续要支持 App 在分身系统内使用，需要把同一个 APK 安装到 user10，再从分身系统打开 UClone Restore，运行同一个「分身系统调试」。

这一步用来确认：

- 分身系统内运行的 UClone Restore 是否能拿到 root。
- 分身系统内的 UClone Restore 是否能访问 `/data/user/0`。
- 分身系统内生成的日志是否仍写入同一个 `/data/adb/uclone/logs`。
- user10 运行态下是否能正确读取 user0/user10 的包名、UID 和 AppOps。

## Landing Gate

完整双向备份模型落地前仍需确认：

- user0 运行 UClone Restore 时，读取 user10 数据稳定。
- user10 运行 UClone Restore 时，读取 user0 数据是否可行已有日志证据。
- user0 读取 user10 CE 前，自动解锁 gate 能区分 `NOT_STARTED`、`LOCKED`、`READY`，并在开关关闭时拒绝继续。
- manifest 能区分来源用户、目标用户和备份类型。
- UI 数据页能按 `sourceUser`、`targetUser`、`backupKind` 分组。
- 恢复前仍强制生成当前目标用户的被动备份。

当前已先落地 user0 -> user10 的实验性快捷推送路径，作为 0.2 双向同步前的可测闭环。
