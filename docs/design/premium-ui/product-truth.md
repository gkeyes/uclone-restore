# UClone UI 产品真相账本

- 基线：当前 `需求.md`、`docs/ARCHITECTURE.md`、`docs/INVARIANTS.md` 与生产代码
- 目标：把不允许由视觉重构改变的产品语义集中为唯一审阅表

## 1. 不可变产品语义

| ID | 产品真相 | 等级 | UI 影响 |
| --- | --- | --- | --- |
| PT-01 | 主 App 执行 Root、备份、恢复、回滚、通知和历史；模块只提交请求。 | `PROJECT-VERIFIED` | 模块不得出现“本地执行 Root”的误导。 |
| PT-02 | 默认主系统为 user0、分身为 user10，运行设置才是最终值。 | `PROJECT-VERIFIED` | 文案用“主系统/分身系统”，技术值作为次级信息。 |
| PT-03 | 切换方向是当前 user10 数据到 user0。 | `PROJECT-VERIFIED` | “切换到分身态”必须说明改变的是主系统 App 当前数据。 |
| PT-04 | 还原方向是保存的 user0 返回点到 user0。 | `PROJECT-VERIFIED` | 禁止使用模糊的“恢复到主系统”而不说明数据来源与目标。 |
| PT-05 | 推送方向是当前 user0 数据到 user10。 | `PROJECT-VERIFIED` | 必须提示会覆盖分身 App 当前数据。 |
| PT-06 | 主动快照、固定 MAIN 返回点、旧版 CLONE 状态备份、事务撤销、分身回滚是不同对象。 | `PROJECT-VERIFIED` | 数据页必须用来源和用途区分，不能只写“备份”。 |
| PT-07 | 固定 MAIN 返回点不能替代本次修改前的新鲜事务撤销。 | `PROJECT-VERIFIED` | 设置说明不得宣称会关闭事务保护。 |
| PT-08 | `需求.md` 要求：`MAIN` 表示 user0 已知为主数据；`CLONE` 表示 user0 已知为分数据且有有效 MAIN 返回点；无法证明则是 `UNKNOWN`。 | `PROJECT-VERIFIED` | 这是目标产品要求，不等于当前代码已满足。 |
| PT-08A | 为兼容旧工作区，当前 `WorkspaceIndex.dataState` 与 `UiState.dataStateFor` 在读取成功、没有 UNKNOWN 记录且没有 switch marker 时仍返回 `Main`；但固定 MAIN 的手动替换只接受成功 MAIN 还原写入的显式确认标记。 | `PROJECT-VERIFIED` | 旧状态可继续切换；危险的固定返回点替换不能把“缺标记”当作充分证据。 |
| PT-09 | 清日志、普通刷新和显示状态重置不能改变真实 App 数据来源。 | `PROJECT-VERIFIED` | “重置状态”只能让 UI 进入未知/重新检查，不能伪造 MAIN。 |
| PT-10 | 工作区新写入数据为 `root:root`；恢复到真实 App 后使用目标 App UID/GID 和 SELinux context。 | `PROJECT-VERIFIED` | 维护页需明确“修备份归属”不是修 App 数据权限。 |
| PT-11 | 旧工作区归属修复必须由用户明确触发，不能在启动、列表刷新或开机自动运行。 | `PROJECT-VERIFIED` | 扫描与修复分开；修复需要确认和任务进度。 |
| PT-12 | 权限/AppOps 迁移在 0.3 是 best effort；捕获失败只警告并跳过，不能撤销、reset 或中止有效文件复制。 | `PROJECT-VERIFIED` | 成功带警告必须显示“数据已完成，权限部分跳过”。 |
| PT-13 | ROM 行为只能由真机命令证据确认，不能从命令名称、exit code 或 AOSP 假设生产行为。 | `PROJECT-VERIFIED` | 诊断信息需保留原始证据，不用“理论支持”冒充已验证。 |
| PT-14 | 跨用户安装只启用系统已有 package/code path，不复制 `/data/app`。 | `PROJECT-VERIFIED` | “安装到另一侧”不能写成 APK 传输。 |
| PT-15 | 安装成功但权限或同步失败是部分成功，不自动卸载。 | `PROJECT-VERIFIED` | 安装、权限、数据三个阶段分别显示结果。 |
| PT-16 | 切换策略由三个独立设置组成：MAIN 返回点是固定或在离开显式 MAIN 时刷新；返回 MAIN 时同步或不更新 user10；失败保护是安全或危险快速。后两项组合成 `SYNC_SAFE`（3 次写入）、`SYNC_FAST`（2 次）、`DISCARD_SAFE`（2 次且不访问 user10）、`DISCARD_FAST`（1 次且不访问 user10）。 | `PROJECT-VERIFIED` | 设置和确认文案必须显示最终计划、完整写入次数、user10 是否变化及本地检查点差异。 |
| PT-16B | 安全计划的新鲜本地撤销只保护本次操作；快速计划省略该检查点。同步计划在 user10 同步失败时不得开始 MAIN 还原；丢弃计划不得读取、启动或修改 user10。快速计划失败进入 `RECOVERY_REQUIRED`，不得谎称执行过自动回滚。 | `PROJECT-VERIFIED` | 错误提示必须区分 user10 可能部分写入、当前分数据已丢弃和 user0 状态未知。 |
| PT-16A | 正常切换到 CLONE 始终读取 user10 当前数据；旧版 `persistent_clone` 不参与正常切换。 | `PROJECT-VERIFIED` | 设置页将该来源作为固定规则展示，不提供开关。 |
| PT-16C | 主 App、桌面快捷入口和模块均可执行四种返回组合；入口不覆盖策略。`ExternalActionService` 接受请求后从同一份持久设置快照派生计划。 | `PROJECT-VERIFIED` | 外部入口不要求再次弹窗，但任务通知、历史和日志必须显示实际计划与结果。 |
| PT-17 | 0.4 的事务 journal、App gate、启动恢复和合作式取消不属于当前 0.3 UI 的已实现能力。 | `PROJECT-VERIFIED` | 当前 UI 不得宣称断电级事务恢复。 |

## 2. 术语契约

| 内部概念 | 面向用户主文案 | 次级技术文案 | 禁止文案 |
| --- | --- | --- | --- |
| `MAIN` | 主数据正在使用 | user0 数据来源已确认 | 正常、未切换（缺少来源语义） |
| `CLONE` | 分数据正在使用 | user0 当前为分身数据；可还原主数据 | 分身系统正在运行（混淆用户生命周期） |
| `UNKNOWN` | 当前数据来源待确认 | marker/工作区状态不足 | 主数据、已安全还原 |
| active snapshot | 主动快照 | 用户建立的恢复源 | 自动回滚 |
| persistent MAIN | MAIN 返回点 | 首次切换时建立；可固定保留、在离开显式 MAIN 时自动刷新，或在 App 详情手动更新 | 本次事务撤销 |
| persistent CLONE | 旧版 CLONE 状态备份 | 旧版本遗留、只作用户管理 | 分身当前实时数据、正常切换来源 |
| transaction undo | 本次操作前保护 | 覆盖目标前新建 | 长期备份 |
| clone rollback | 分身覆盖前保护 | 推送前保存的 user10 数据 | 主系统备份 |
| push | 更新分身数据 | user0 -> user10 | 切换、同步双向 |
| restore main | 还原主数据 | MAIN 返回点 -> user0 | 恢复到主系统 |
| switch clone | 在主系统使用分数据 | user10 -> user0 | 启动分身系统 |

## 3. 证据边界

### `OFFICIAL-VERIFIED`

Apple HIG 和 Android 官方文档只用于平台模式、可访问性、布局与组件约束。Apple HIG 不对 Android 实现产生平台合规声明。

### `PROJECT-VERIFIED`

当前 `需求.md`、生产状态模型和调用链是产品语义的权威来源。

### `DERIVED`

“系统工具应状态优先、动作次之”“高风险动作应分层”等结论由产品风险和官方模式共同推导，需在方向审批后成为实现合同。

### `EXPERIMENTAL`

任何导航降维、数据页并入 App、诊断并入设置、三栏 iPad/平板式布局，均未获批准。

### `UNVERIFIED`

深色模式、大字体、TalkBack、平板、横屏、加载/错误截图在当前分支均无新的运行证据。

### `PROHIBITED-AS-EVIDENCE`

- 旧 0.2/0.3 分支截图。
- Apple 官方 UI Kit 截图中的像素尺寸或具体业务布局。
- 概念稿、静态 mockup、设计工具预览。
- 非当前 GitHub 构建产物的手机截图。

## 4. 设计变更审批规则

以下变化必须单独获得批准：

1. 删除任何页面或动作。
2. 把顶层入口移入另一个页面。
3. 新增后台行为、自动扫描、自动修复或自动删除。
4. 改变 MAIN/CLONE/UNKNOWN 的判定。
5. 改变确认、事务保护、权限迁移或分身生命周期语义。
6. 引入 Compose Navigation、第三方设计系统或新的持久化字段。

视觉实现可以改变构图、组件和信息层级，但不能越过以上产品边界。
