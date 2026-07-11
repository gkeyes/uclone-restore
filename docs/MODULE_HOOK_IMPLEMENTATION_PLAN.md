# UClone Launcher Module 技术实现方案

- 文档版本: 0.1 draft
- 对应阶段: UClone Restore 0.2 模块化入口预研
- 更新日期: 2026-07-09

> 状态说明（2026-07-10）：本文保留最初的中继方案作为设计历史。现行协议以 `docs/EXTERNAL_ACTION_PROTOCOL.md` 为准：`ModuleRelayProvider` 校验 Launcher 调用方后，返回由模块 UID 创建、直接指向 UClone `ExternalActionService` 的一次性前台服务 `PendingIntent`。透明 Activity 中继在 Android 14+ 可能被后台启动策略异步拦截，已停止用于新请求。

## 1. 目标

模块目标是在桌面长按目标 App 图标时增加 UClone 操作入口，让用户不打开 UClone Restore 主界面也能触发常用动作。

第一版只做一个入口:

```text
UClone 切换/还原
```

它调用 UClone 的 `SWITCH_OR_RESTORE`，由 UClone 根据当前 switch marker 自动判断是切换到分身态还是还原主系统态。

## 2. 核心边界

模块只负责入口，不负责真实数据操作。

| 边界 | 归属 |
| --- | --- |
| Hook Launcher 长按菜单 | 模块 |
| 提取 targetPackage / component / userHandle | 模块 |
| 每个 App 是否显示菜单 | 模块 |
| root 文件复制 | UClone Restore |
| 快照 / 回滚 / 推送 / 恢复 | UClone Restore |
| user10 自动解锁 | UClone Restore |
| UID/GID/SELinux 修正 | UClone Restore |
| busy gate / 任务日志 | UClone Restore |
| 系统 App / UClone 自身拦截 | UClone Restore 必须再次判断 |

模块不读取 `/data/user/*`，不访问 `/data/adb/uclone`，不执行 `su`。

Hook 层也不直接读取模块私有配置。由于 Hook 运行在 Launcher 进程中，直接读取模块的 `DataStore`、`SharedPreferences` 或 Room 数据库不稳定。Hook 层必须通过 `ModuleRelayProvider.queryMenuState(...)` 查询是否显示菜单、菜单文案和 `PendingIntent`。

## 3. 关键技术修正

LSPosed Hook 代码运行在被 Hook 的进程里。对桌面长按菜单来说，Hook 代码运行在:

```text
com.miui.home
com.android.launcher3
```

因此 Hook 代码直接调用 UClone 的 `signature` Service 时，Android 权限检查看到的调用方通常是 Launcher UID，而不是模块 APK UID。

错误路径:

```text
Launcher Hook
  -> context.startForegroundService(UClone ExternalActionService)
  -> caller = Launcher
  -> Launcher 不持有 com.uclone.restore.permission.CONTROL
  -> SecurityException 或启动失败
```

正确路径:

```text
Launcher Hook
  -> ModuleRelayProvider.queryMenuState(...)
  -> Provider 以模块 UID 创建 PendingIntent.getForegroundService(...)
  -> Hook 仅调用 pendingIntent.send()
  -> UClone ExternalActionService 以 PendingIntent 创建者身份校验并执行任务
```

`com.uclone.restore.permission.CONTROL(signature)` 仍然有价值。`PendingIntent` 的创建者是同签名模块 APK，因此权限校验针对模块 UID，而不是 Launcher UID。

## 4. 推荐架构

```text
UClone Launcher Module APK
├─ SettingsActivity
├─ HookEntry
├─ ModuleConfigStore
├─ ModuleRelayProvider
├─ PendingIntentFactory
├─ LegacyRelayComponents（仅兼容旧 token，不签发新 token）
└─ HookEventLog

UClone Restore APK
├─ ExternalActionService
├─ ExternalActionContract
├─ ExternalQueryProvider / ExternalQueryService
├─ ActionStateResolver
└─ SyncEngine
```

### 4.1 HookEntry

运行在 Launcher 进程中。

职责:

- 识别长按目标。
- 提取 `targetPackage`、`targetComponent`、`targetUserHandle`。
- 判断目标是否是普通 App 图标。
- 调用 `ModuleRelayProvider.queryMenuState(...)`。
- 注入一个最小菜单项。

禁止:

- 禁止直接调用 UClone Service。
- 禁止执行 root 命令。
- 禁止直接判断 UClone 当前状态。
- 禁止直接读写 UClone 工作目录。
- 禁止直接读取模块私有配置。

稳定性要求:

- 所有 Hook 逻辑必须包在 `try/catch` 中。
- 找不到类、字段、方法、菜单容器时直接跳过。
- 异常必须写入 `HookEventLog`。
- 连续 Hook 异常超过阈值时自动禁用 Hook，避免 Launcher 循环崩溃。

### 4.2 ModuleRelayProvider

运行在模块 APK 自身进程。

职责:

- 接收 Hook 层传来的包名、组件和 userHandle。
- 使用 `Binder.getCallingUid()` 校验调用方必须是允许的 Launcher。
- 校验模块全局开关。
- 校验 App 白名单。
- 校验操作白名单。
- 创建显式指向 UClone `ExternalActionService` 的一次性前台服务 `PendingIntent`。

推荐 Provider API:

```text
content://com.uclone.restore.module.relay
method=queryMenuState
```

输入:

```text
operation = SWITCH_OR_RESTORE
packageName = <target package>
requestId = <uuid>
componentName = <optional component>
targetUserHandle = <optional launcher user handle>
```

输出:

```text
status = ACCEPTED / REJECTED
requestId = <uuid>
showMenu = true / false
menuLabel = UClone 切换/还原
pendingIntent = <PendingIntent?>
message = <reason>
```

Manifest 要求:

```xml
<provider
    android:name=".relay.ModuleRelayProvider"
    android:authorities="com.uclone.restore.module.relay"
    android:exported="true" />
```

Provider 必须 `exported=true`，因为调用方是 Launcher。不要用 UClone 的 `signature` permission 保护 Provider；Launcher 不会持有该权限。Provider 内部必须用 `Binder.getCallingUid()` 和 `PackageManager.getPackagesForUid()` 校验调用方是否属于允许的 Launcher，例如 `com.miui.home`。

### 4.3 PendingIntentFactory

最稳方案是由模块 Provider 返回一个模块进程创建的 `PendingIntent`。

Hook 层只做:

```kotlin
pendingIntent.send()
```

这样可以避免 Hook 代码直接以 Launcher 身份启动 UClone。

现行 PendingIntent 目标是 UClone 的 `ExternalActionService`:

```text
Hook -> ModuleRelayProvider -> module-owned PendingIntent.getForegroundService -> UClone ExternalActionService
```

这样既确保权限校验使用模块 APK UID，又避免后台 Activity trampoline 在 Android 14+ 被异步拦截。PendingIntent 必须使用显式组件、`FLAG_IMMUTABLE`、`FLAG_ONE_SHOT` 和 request-specific data URI。

### 4.4 唯一入口边界

旧 `ModuleRelayActivity`、`ModuleRelayService` 和 `ModuleRelayDispatcher` 已删除。Provider 返回的显式、一次性前台服务 PendingIntent 是唯一现行入口，不再保留 Activity 或 Service trampoline。

## 5. UClone 侧接口

### 5.1 已有执行接口

UClone 当前已有:

```text
com.uclone.restore/.external.ExternalActionService
```

Action:

```text
com.uclone.restore.action.EXECUTE
```

权限:

```text
com.uclone.restore.permission.CONTROL
```

协议版本:

```text
1
```

Manifest 要求:

```xml
<service
    android:name=".external.ExternalActionService"
    android:exported="true"
    android:permission="com.uclone.restore.permission.CONTROL" />
```

模块 APK 必须声明:

```xml
<uses-permission android:name="com.uclone.restore.permission.CONTROL" />
```

模块和 UClone 必须使用同一签名，否则该 `signature` 权限不会授予模块。

第一版模块只调用:

```text
SWITCH_OR_RESTORE
```

### 5.2 需要新增的查询接口

模块不应该自己判断按钮文案和状态。UClone 应提供只读查询接口。

建议二选一:

```text
ExternalQueryProvider
ExternalQueryService
```

建议 Action:

```text
com.uclone.restore.action.QUERY
```

建议 operation:

```text
QUERY_ACTION_STATE
```

输入:

```text
packageName = <target package>
protocolVersion = 1
```

输出:

```json
{
  "packageName": "com.example.app",
  "state": "READY_SWITCH_OR_RESTORE",
  "primaryAction": "SWITCH_OR_RESTORE",
  "primaryLabel": "UClone 切换/还原",
  "busy": false,
  "hasActiveSnapshot": true,
  "hasRollback": false,
  "needUser10Unlock": false,
  "riskLevel": "NORMAL",
  "errorCode": null,
  "message": null
}
```

查询结果只用于显示。执行前 UClone 仍需重新校验所有安全规则。

### 5.3 状态广播

UClone 已有:

```text
com.uclone.restore.action.STATUS
```

建议扩展字段:

```text
REQUEST_ID
PACKAGE_NAME
OPERATION
STATUS
ERROR_CODE
MESSAGE
TASK_TYPE
```

广播发送要求:

```kotlin
Intent("com.uclone.restore.action.STATUS")
    .setPackage(modulePackageName)
```

不要发送全局隐式广播。模块应按 `REQUEST_ID` 关联即时 ACK 和最终状态。

状态分两级:

| 状态 | 用途 |
| --- | --- |
| `ACCEPTED` | UClone 已接收任务 |
| `SUCCESS` / `FAILED` / `REJECTED` / `BUSY` | 任务最终或拒绝结果 |

诊断链还记录 `MENU_READY`、`SENT`、`SERVICE_RECEIVED`、`RUNNING`、`SUCCESS_WITH_WARNINGS`、`INTERRUPTED`、`STILL_RUNNING`、`ORPHANED` 与 `FAILED_PROCESS_DIED`，并始终按 `REQUEST_ID` 关联。

## 6. 安全规则

### 6.1 模块侧

模块必须校验:

- 调用 Provider 的 UID 属于允许 Launcher。
- Launcher 包名在白名单中，例如 `com.miui.home`。
- 模块全局开关开启。
- 目标 App 在模块白名单中。
- 目标操作在该 App 的操作白名单中。
- 目标不是模块自身。
- 目标不是 UClone Restore。
- 默认不允许系统 App。
- 第一版只允许 user0 普通 App 图标。
- 分身图标、deep shortcut、文件夹、小组件、未知 userHandle 只记录日志，不注入菜单。

模块侧校验只用于减少误触。不能代替 UClone 侧校验。

### 6.2 UClone 侧

UClone 必须再次校验:

- `allowModuleControl` 是否开启。
- 调用方是否持有 `com.uclone.restore.permission.CONTROL`。
- protocol version 是否匹配。
- operation 是否在允许列表。
- `targetPackage` 是否有效。
- `targetPackage` 是否是 UClone 自身。
- `targetPackage` 是否系统 App / updated-system App。
- 是否已有任务运行。
- `SWITCH_TO_CLONE` 是否会覆盖已有 switch marker。
- `RESTORE_MAIN` 是否存在 switch marker。
- `PUSH_MAIN_TO_CLONE` 是否需要确认。

`SOURCE` extra 只允许用于日志，不能用于鉴权。

### 6.3 高风险动作

第一版不在长按菜单暴露:

```text
PUSH_MAIN_TO_CLONE
SWITCH_TO_CLONE
RESTORE_MAIN
BACKUP_DEFAULT
RESTORE_LATEST_BACKUP
RESTORE_LATEST_CLONE_ROLLBACK
```

其中 `PUSH_MAIN_TO_CLONE` 是覆盖分身数据，未来即使开放，也必须走确认:

需要确认的动作只保留在 UClone 主界面，不通过桌面模块暴露。

不要允许静默推送。

## 7. Hook 落地路径

### Step 1: 探测目标包名

只打日志，不注入菜单。

记录:

```text
launcherPackage
launcherVersion
launcherProcess
itemInfoClass
targetPackage
targetComponent
targetUserHandle
container
screen
```

必须区分:

- 普通 App 图标
- 文件夹
- 小组件
- Deep shortcut
- 系统快捷方式
- 分身图标

第一版只处理 user0 普通 App 图标。

### Step 2: 探测菜单对象

只打日志，不注入菜单。

记录:

```text
popupClass
menuContainerClass
adapterClass
itemViewClass
shortcutItemClass
clickHandlerClass
```

目标是确认 HyperOS Launcher 当前版本的菜单结构。

### Step 3: 注入最小菜单项

只注入:

```text
UClone 切换/还原
```

不做多按钮，不做复杂图标，不做动态状态文案。

点击后:

```text
Hook -> ModuleRelayProvider -> module-owned foreground-service PendingIntent -> UClone 通知：任务已接收
```

### Step 4: 接入 UClone 查询接口

UClone 增加 `QUERY_ACTION_STATE` 后，模块再显示 UClone 返回的文案。

例如:

```text
UClone 切换
UClone 还原
UClone 忙碌中
需要打开分身解锁
```

模块不自行读取 switch marker，也不直接判断 active snapshot。

### Step 5: 状态反馈

模块监听 UClone status broadcast。

记录:

```text
requestId
packageName
operation
ackStatus
finalStatus
message
taskType
timestamp
```

第一版可以只记录日志和 Toast。后续再做通知或设置页历史。

## 8. 模块设置页

第一版设置项:

```text
启用 Launcher Hook
LSPosed 作用域提示: com.miui.home
App 白名单勾选列表
每个 App 是否显示 UClone 菜单
签名/权限检测
最近 Hook 事件
最近请求日志
```

App 白名单不手填包名。模块设置页读取当前 user0 可启动 App 列表，显示 App 名称和包名复选框，用户勾选后保存为包名集合。普通设置页不暴露 Launcher 包名输入框，避免和目标 App 白名单混淆。LSPosed 作用域第一版只需要选择 `com.miui.home`；目标 App 是否出现 UClone 菜单由模块设置页的 App 勾选列表控制。

每个 App 的操作权限第一版固定:

```text
SWITCH_OR_RESTORE = enabled
其他操作 = disabled
```

后续版本再增加:

```text
允许推送到分身
允许主动备份
允许恢复最新备份
```

签名/权限检测必须包含:

```text
是否安装 UClone
UClone 版本是否兼容
模块和 UClone 是否同签名
CONTROL 权限是否授予
ExternalActionService 是否可解析
ModuleRelayProvider 是否可用
```

## 9. 调试日志

模块必须有两个开发期日志页。

### 9.1 Hook 事件日志

```text
time
launcherPackage
launcherVersion
itemInfoClass
targetPackage
targetComponent
targetUserHandle
popupClass
injectStatus
error
consecutiveErrorCount
autoDisabled
```

### 9.2 Relay 请求日志

```text
time
requestId
callerUid
callerPackages
operation
packageName
relayStatus
ucloneAck
finalStatus
message
```

这些日志是适配 HyperOS Launcher 的核心证据，不应省略。

## 10. UClone 侧后续任务

为了支持模块稳定落地，UClone 侧需要补:

1. `ExternalQueryProvider` 或 `ExternalQueryService`
2. `QUERY_ACTION_STATE`
3. `ActionStateResolver`
4. `PUSH_MAIN_TO_CLONE` 的确认机制
5. 更明确的模块操作白名单设置
6. status broadcast 增加 `ERROR_CODE`
7. STATUS 广播显式 `setPackage(modulePackage)`

当前 `ModuleRelayProvider` 负责 Launcher 调用方、配置和白名单校验；UClone `ExternalActionService` 负责签名权限、目标包、操作和并发状态的二次校验。

## 11. 第一版 MVP

第一版只承诺:

- 仅适配当前测试机 HyperOS Launcher。
- 仅 Hook `com.miui.home`。
- 仅处理 user0 普通 App 图标。
- 仅白名单 App 显示菜单。
- 仅显示一个入口: `UClone 切换/还原`。
- 仅调用 `SWITCH_OR_RESTORE`。
- 不显示推送到分身。
- 不显示强制切换或强制还原。
- 不直接打开 UClone 主界面。

## 12. 验收标准

### 12.1 Hook 探测

- 长按普通 App 图标能记录正确 `targetPackage`。
- 文件夹、小组件、deep shortcut 不注入菜单。
- 分身图标能记录 `userHandle`，即使第一版不处理。
- 未知 userHandle 不注入菜单。
- Hook 异常不会导致 Launcher 崩溃。
- 连续异常超过阈值后自动禁用 Hook。

### 12.2 Provider 与 PendingIntent

- Hook 调 Provider 成功。
- 非 Launcher 调 Provider 被拒绝。
- 未在白名单的 App 被拒绝。
- Provider 能返回 `PendingIntent`。
- Provider `queryMenuState` 能返回 `showMenu`、`menuLabel` 和 `PendingIntent`。
- Hook 调 `pendingIntent.send()` 后 UClone `ExternalActionService` 收到请求。
- PendingIntent creator 为模块 UID，UClone 的 signature 权限校验通过。
- Provider `exported=true` 且不用 signature permission 保护。
- 新 token 不经过旧 Relay Activity/Service。

### 12.3 UClone 执行

- 模块控制关闭时 UClone 拒绝。
- 模块未获 `CONTROL` 权限时 UClone 拒绝。
- UClone 自身被拒绝。
- 系统 App 默认被拒绝。
- 已有任务运行时返回 `BUSY`。
- 未切换时 `SWITCH_OR_RESTORE` 执行切换。
- 已切换时 `SWITCH_OR_RESTORE` 执行还原。
- 重复点击不会覆盖原始回滚点。

### 12.4 用户体验

- 点击菜单后不打开 UClone 主界面。
- UClone 前台通知显示任务执行中。
- 操作完成后有成功或失败反馈。
- 失败能在 UClone 日志中定位原因。
- STATUS 广播显式发送给模块 package。

## 13. 风险

### 13.1 Launcher Hook 不稳定

HyperOS Launcher 内部类和菜单结构可能随版本变化。第一版只适配当前测试机，不承诺通用 HyperOS。

### 13.2 Android 前台服务限制

Android 12+ 对后台启动前台服务有限制。现行方案由可见 Launcher 用户操作触发模块创建的前台服务 PendingIntent；不要退回透明 Activity trampoline。

### 13.3 多用户图标识别

Launcher item 可能代表不同 user 的同一包名。第一版记录 `targetUserHandle`，但执行仍由 UClone 现有 user0/user10 模型决定。

### 13.4 高风险误触

长按菜单离真实 App 很近，误触概率高。第一版禁止静默推送到分身。

### 13.5 LSPosed / Android 版本兼容

LSPosed 本身和 Android 新版本存在兼容性不确定性。第一版不要把模块做成通用分发能力，只针对当前测试机的系统版本、KernelSU/LSPosed 环境和 HyperOS Launcher 版本落地。

## 14. 最终架构图

```text
用户长按 App 图标
  -> com.miui.home Hook
  -> 提取 targetPackage / component / userHandle
  -> ModuleRelayProvider.queryMenuState(...)
  -> Provider 校验 caller = allowed Launcher
  -> Provider 校验 App 白名单 / 操作白名单
  -> Provider 返回 showMenu / menuLabel / module-owned foreground-service PendingIntent
  -> Hook pendingIntent.send()
  -> UClone ExternalActionService
  -> UClone 校验 allowModuleControl / signature / package / operation / busy / marker
  -> SyncEngine 执行 SWITCH_OR_RESTORE
  -> 前台通知 / 显式 STATUS 广播 / 日志
```

## 15. 结论

模块方案可行，但必须以身份边界和双重校验作为第一原则:

```text
Hook 不直接调用 UClone。
Provider 只向通过校验的 Launcher 返回模块 UID 创建的 PendingIntent。
UClone 决定状态和执行。
```

第一版应极度收敛，只做 `SWITCH_OR_RESTORE`。推送、备份、恢复最新备份都保留协议，不进入默认长按菜单。
