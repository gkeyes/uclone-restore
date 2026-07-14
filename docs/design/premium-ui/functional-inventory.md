# UClone UI 功能清单

- 审计日期：2026-07-13
- 审计分支：`design/premium-system-utility-ui`
- 审计模式：`audit + art-direction + specification`
- 产品平台：Android 主 App + Android Launcher 模块
- 视觉参考：Apple iOS/iPadOS HIG；Android 实现约束以 Android/Material 官方文档为准
- 当前阶段：五项顶级导航和 Liquid Glass UI 已实现，等待固定签名 APK 真机验收

## 证据等级

- `PROJECT-VERIFIED`：由当前分支的需求、生产代码或状态模型直接证明。
- `DERIVED`：由多个已验证事实推导，仍需在实现或真机阶段验证。
- `EXPERIMENTAL`：候选设计，不得在未批准时实施。
- `UNVERIFIED`：尚无当前分支运行截图或真机证据。
- `PROHIBITED-AS-EVIDENCE`：旧分支截图、概念图和 Apple 官方素材不能证明当前 Android 实现状态。

## 1. 系统边界

`PROJECT-VERIFIED`

```text
主 App Compose UI / Launcher 模块
-> ExternalActionService
-> TaskCoordinator
-> ExternalActionDispatcher
-> SyncEngine
-> POSIX shell
-> RootShellExecutor
-> user0 / user10 / UClone workspace
```

主 App 拥有 Root、备份、恢复、回滚、通知和任务历史。Launcher 模块只负责注入入口、选择目标 App、提交经过认证的请求以及显示模块自身诊断信息。

## 2. 主 App 导航清单

当前导航定义位于 `app/src/main/java/com/uclone/restore/ui/UCloneApp.kt`。

| 层级 | 页面 | 当前入口 | 功能责任 | 主要数据源 | 当前状态 |
| --- | --- | --- | --- | --- | --- |
| 顶层 | 首页 | 底部导航、桌面收藏快捷入口 | 系统状态摘要、分身生命周期、最新任务、收藏 App 快捷动作 | `EnvironmentStatus`、`TaskProgress`、`favoriteApps`、`AppDataState` | `PROJECT-VERIFIED` |
| 顶层 | App | 底部导航 | 搜索、筛选、收藏、进入 App 详情 | `PackageInspector`、`UiState.apps`、设置 | `PROJECT-VERIFIED` |
| 顶层 | 数据 | 底部导航 | 主动快照、主系统被动备份、分身回滚及容量 | `WorkspaceIndex`、`RestoreBackupEntry` | `PROJECT-VERIFIED` |
| 顶层 | 历史 | 底部导航 | 任务时间、状态、结果、日志路径、将任务包名设为当前选中 App | `TaskRepository`、`UCloneViewModel.selectPackage` | `PROJECT-VERIFIED` |
| 顶层 | 设置 | 底部导航 | 用户、工作区、数据范围、生命周期、模块、维护与重置；进入诊断与维护 | `SettingsStore`、`WorkspaceOwnershipReport` | `PROJECT-VERIFIED` |
| 二级 | 诊断 | 设置 → 诊断与维护 | Root、用户、CE/DE、目录探测和分身调试 | `EnvironmentStatus`、显式诊断任务 | `PROJECT-VERIFIED` |
| 详情 | App 详情 | 首页收藏或 App 列表 | 安装状态、数据状态、数据范围、主动快照、切换/还原、审计和跨用户安装等当前上下文动作 | 选中 App、规则、备份、marker、任务状态 | `PROJECT-VERIFIED` |
| 详情 | 备份详情 | 数据页 | 查看并恢复或删除主动快照、主系统被动备份；当前不解析分身回滚详情 | 选中包名、rollback ID、主系统备份索引 | `PROJECT-VERIFIED` |

### 当前导航实现与限制

- `PROJECT-VERIFIED`：紧凑手机底部栏包含首页、App、数据、历史、设置 5 个顶层入口。
- `PROJECT-VERIFIED`：诊断功能完整保留，并通过“设置 → 诊断与维护”进入；返回后仍位于设置。
- `PROJECT-VERIFIED`：详情返回栈仍由 `rememberSaveable` 和手工枚举维护。
- `PROJECT-VERIFIED`：分身回滚当前只提供列表行内恢复，不提供详情入口；主动快照和主系统被动备份继续使用现有详情页。
- `UNVERIFIED`：五项玻璃底栏在深色模式、大字体、TalkBack 和连续滚动下的视觉及性能表现仍需固定签名 APK 真机验收。

## 3. 主 App 页面功能

### 首页

- 展示 Root、当前用户、分身用户与 CE 状态。
- 刷新环境。
- 启动分身、关闭分身。
- 展示当前/最近任务。
- 收藏 App：进入详情、推送、切换到分身态、还原主数据。
- 所有覆盖性操作继续显示确认。

### App 列表

- 搜索已识别 App。
- 按现有筛选条件过滤。
- 收藏或取消收藏。
- 进入 App 详情。
- App 图标和标签来自 PackageManager/现有缓存，不增加新数据源。

### App 详情

- 显示 user0/user10 安装状态、UID 与风险等级。
- 跨用户安装：仅安装、安装并迁移权限、安装并同步数据。
- 显示 `MAIN`、`CLONE`、`UNKNOWN` 数据状态。
- 显示主动快照信息。
- 配置 CE、DE、external、media、OBB、权限/AppOps、排除缓存。
- 执行：切换/还原、建立主动快照、恢复主动快照、从分身最新数据备份并恢复、生成审计包、删除主动快照。
- 显示当前任务和步骤。

### 数据页与备份详情

- 区分主动快照、主系统被动备份、分身回滚。
- 展示来源、时间、大小、状态标签和 rollback ID。
- 恢复指定被动备份。
- 分身回滚行只提供行内恢复，不响应整行详情导航；不得写成已支持分身回滚详情。
- 删除指定主动快照或主系统被动备份；当前没有删除分身回滚的 UI 动作。
- 删除语义不得与卸载 App、清日志或普通刷新混淆。

### 历史

- 展示 request/task 对应的包名、任务类型、开始/结束、状态、结果和日志路径。
- 能从记录选择目标 App。
- 任务文案必须以领域映射展示，不能以内部枚举替代用户文案。

### 设置

- 主系统/分身 userId 与 Root 工作目录。
- CE/DE/external/media/OBB/权限/AppOps/cache 规则。
- 自动解锁、任务后关闭分身、模块控制、分身凭据。
- 三项独立切换策略：MAIN 返回点固定/离开显式 MAIN 时刷新、返回 MAIN 时同步/不更新 user10、安全/危险快速保护；页面同时显示派生的四种执行计划、完整写入次数和失败影响。
- App 详情中的“更新固定 MAIN 返回点”仅在状态明确为 MAIN 时可执行，不向模块或桌面快捷入口开放。
- 收藏由 App 列表/详情维护；桌面快捷入口由现有同步控制器跟随收藏状态更新，不属于设置页字段。
- 扫描/修复工作区归属。
- 清理日志、重置工作区。

### 诊断

- 环境重新检测。
- 检测 CE 状态。
- 分身系统调试。
- 无感启动/解锁分身。
- 显式启动或切换到分身；关闭分身动作当前只在首页，不在诊断页。
- 技术原值仅作为次级信息，不能替代面向用户的中文状态。

## 4. Launcher 模块功能

当前模块页面定义位于 `launcher-module/src/main/java/com/uclone/restore/module/settings/ModuleSettingsActivity.kt`。

| 页面 | 功能 | 数据来源 | 当前状态 |
| --- | --- | --- | --- |
| 状态 | Hook 总开关、签名/权限/组件可发现性、自动禁用、连续 Hook 异常计数、目标 App 数量、进入 App 选择 | 模块设置、PackageManager、已记录 Hook/Relay 事件 | `PROJECT-VERIFIED` |
| App | 已选择/用户 App/全部 App 筛选、逐 App 启用、保存 | PackageManager、`ModuleSettingsStore` | `PROJECT-VERIFIED` |
| 控制 | Hook、作用域说明、自动禁用重置、保存 | 模块设置、权限状态 | `PROJECT-VERIFIED` |
| 日志 | 全部/Hook/Relay 筛选、刷新、复制、清空 | 模块日志存储 | `PROJECT-VERIFIED` |

模块当前使用原生 Android View 代码生成界面，不引入 Compose 或 SwiftUI 作为本轮前置条件。

模块“状态”页是配置、组件可发现性和历史错误计数的派生摘要。它不持续探测 Launcher 中 Hook 是否正在执行，也不证明某次 Relay 请求当前可达，因此视觉文案不得把这些检查合并成“实时健康”。

## 5. 全局数据源

| 数据源 | 拥有的数据 | UI 允许的用途 | 禁止推导 |
| --- | --- | --- | --- |
| `SettingsStore` | 用户、路径、数据范围、凭据开关、模块与性能选项 | 展示和编辑设置 | 不得作为实时用户/磁盘/marker 状态 |
| `PackageInspector` | 包、标签、图标、安装用户、UID、风险 | App 列表与详情 | 不得证明数据来源状态 |
| `SyncEngine` / workspace index | 快照、回滚、marker、环境、维护扫描 | 数据页、状态、诊断 | 读取失败时不得保留为已确认旧状态 |
| `TaskRepository` | 历史、进度、步骤、日志摘要 | 任务 UI 与历史 | 不得把传输失败伪装成业务成功 |
| `TaskCoordinator` | 当前 admission/busy | 禁用冲突动作 | 不替代 Root 磁盘锁或终态 |
| `LauncherShortcutController` | 收藏快捷入口状态 | 桌面入口同步 | 不决定 App 数据状态 |
| `ModuleSettingsStore` | Hook/目标 App/模块控制 | 模块 UI | 不拥有 Root 任务执行 |

## 6. 审计保护线

- 本文没有删除、移动或新增功能。
- 本文没有批准任一导航改动。
- 旧截图只可作为历史视觉样本，标记为 `PROHIBITED-AS-EVIDENCE`，不能证明当前分支状态。
- Apple 素材只可证明参考模式，不能证明 Android 已实现或符合 Apple 平台要求。
- 后续生产改动必须逐项映射回本清单，遗漏任一现有动作即阻断合并。
