# UClone Restore 0.3.0 全面审计报告

审计日期：2026-07-11  
审计对象：主 App `0.3.0 (22)`、Launcher 模块 `0.3.0 (5)`

## 审计范围

本轮采用三个相互独立的只读审计，再由主审计线程交叉核实并修复：

1. 核心数据链：快照、切换、还原、推送、回滚、空间预检、CE 生命周期、跨用户安装。
2. 外部执行链：Launcher Hook、Provider、PendingIntent、前台服务、requestId、进程死亡恢复。
3. UI 与状态链：App 选择、数据来源、删除路由、进度、设置持久化、构建与发布准备度。

三个审计均未确认 P0；初审发现的 P1 均由主线程逐项复核。

## 已修复的重要问题

| 领域 | 问题 | 修复 |
|---|---|---|
| 编译 | public ViewModel API 暴露 internal 类型 | 收紧为 internal API |
| 数据来源 | 主系统态和分身态备份互相覆盖、恢复后按钮状态错误 | manifest 持久化 `stateKind`，按实际恢复来源更新切换标记 |
| 回滚清理 | clone-to-clone 恢复可能清除仍有效的主系统还原点 | 只在 marker 指向目录确实缺失时清理 marker |
| 备份复用 | 仅检查 `.state` 文件存在，损坏备份可能被复用 | 执行 shell 校验状态值、data 目录和实际 payload |
| 旧备份 | 无 `stateKind` 的旧备份恢复后留下矛盾状态 | 在目标变更前拒绝来源未知的旧备份并给出明确错误 |
| 分身回滚删除 | 详情页删除错误指向 `rollback/` | 新增独立 `DELETE_CLONE_ROLLBACK` 路由和受限删除脚本 |
| App 详情 | 异步选择导致短暂显示上一个 App | 先同步更新 package selection，再异步刷新元数据 |
| 进度 | 详情页步骤不随 Root 阶段变化 | 直接显示持久化 `currentStage` |
| 自定义工作区 | owner 修复要求 identity，但生产路径从不创建 | 安全校验允许子目录后创建 `workspace.identity` |
| Provider 安全 | 仅按包名信任 Launcher，可形成 confused deputy | 仅允许配置中的系统 Launcher；默认只允许 `com.miui.home` |
| 请求重放 | 进程重启后相同 requestId 可再次执行破坏性任务 | TaskCoordinator 拒绝已有终态 requestId |
| 进程死亡 | Root 仍运行时可能误报 `FAILED_PROCESS_DIED` | 校验 boot ID、PID、start ticks；存活时回报 `STILL_RUNNING` |

## 0.3.0 需求核对

| 能力 | 结果 | 证据 |
|---|---|---|
| 仅安装到另一侧 | 已实现 | `cmd package install-existing --user` |
| 安装并迁移权限 | 已实现 | runtime permission 与 AppOps 差异迁移 |
| 安装并同步数据 | 已实现 | 安装后进入既有 push/restore 事务链 |
| 不复制 `/data/app` | 已满足 | 安装脚本只调用 PackageManager |
| 安装失败不修改数据 | 已满足 | 安装验证在同步前执行 |
| 安装成功、同步失败不卸载 | 已满足 | 返回部分成功，不执行 uninstall |
| UClone 自身默认禁止 | 已满足 | 外部策略与 shell 双重拒绝 |
| 系统 App 默认禁止 | 已满足 | 高级设置显式开启后才允许 |
| user10 CE 按需解锁 | 已满足 | 仅数据同步路径进入 CE gate |
| 主/分数据来源标识 | 已实现 | `stateKind=main/clone` 与 UI 标签 |
| 推送前分身回滚 | 已实现 | 独立 `clone_rollback/<pkg>/latest` |

## 验证结果

- `gradle testDebugUnitTest`：通过。
- 单元测试：189 个，0 failure，0 error。
- `gradle lintDebug assembleDebug`：通过。
- Android Lint：0 Error；剩余为 Warning/Hint。
- `git diff --check`：通过。
- 主 App Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。
- 模块 Debug APK：`launcher-module/build/outputs/apk/debug/launcher-module-debug.apk`。

## 保留风险

1. `specialUse` 前台服务类型用于解决 Android 15/16 后台冷启动的 `dataSync` 配额问题。该方案适合当前个人侧载环境，但若进入 Play 分发，需重新评估平台声明合规性。
2. `ExternalActionService` 与 Launcher Hook 文件仍较大，维护性拆分属于后续重构，不阻塞当前行为。
3. shell 事务、HyperOS user10 解锁和真实 App 登录态只能由 root 真机验证；JVM 测试和 Debug 构建不能替代该步骤。
4. 当前生成的是 Debug APK。正式发布仍需 GitHub Actions 固定签名产物及安装后的签名一致性检查。

## 真机验收清单

1. UClone 未启动时，从小红书桌面菜单执行切换并观察通知、历史、requestId。
2. 连续执行主态 → 分身态 → 主态，确认首页按钮和数据页来源标签一致。
3. 开启复用后分别验证主态、分身态、推送回滚只复用完整备份；破坏 payload 后必须重建。
4. 从数据页删除分身回滚，确认只删除 `clone_rollback/<pkg>/latest`。
5. 仅 user0 安装与仅 user10 安装场景分别执行三种跨用户安装模式。
6. 任务运行中杀死 UClone 界面进程，确认 Root 仍运行时模块收到 `STILL_RUNNING`，而不是失败。

