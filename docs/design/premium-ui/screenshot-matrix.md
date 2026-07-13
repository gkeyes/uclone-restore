# UClone UI 截图与交互验收矩阵

## Status

历史 A1/A2 截图只用于说明被否决的问题，不作为当前实现证据。当前分支已改为 Backdrop 1.0.2 的真实底栏背景采样、五项导航、连续分组列表和短半透明主动作；内容树不反向读取同一个 Backdrop。在安装当前提交对应的 GitHub 固定签名 APK 前，所有真机项保持 `UNVERIFIED`。

## 1. 主 App 核心矩阵

| ID | Window | Theme | Text scale | Input | Screen/state | Screenshot | Interaction test | Result | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| M-01 | compact portrait | light | default | touch | 首页/default/ready | `1fc2831` 用户截图 | 进入所有顶层页；检查系统动作行和底栏选中态 | `REJECTED / RETEST REQUIRED` | 分组面和底栏通过方向检查；实心蓝主动作过重。修正版要求低饱和短胶囊、状态与动作同层、更多工具镜片独立 |
| M-02 | compact portrait | dark | default | touch | 首页/default/ready | 待采集 | 收藏 App 主动作 | `UNVERIFIED` | 状态不只靠颜色 |
| M-03 | compact portrait | light | large | touch | 首页/current task | 待采集 | 展开任务详情 | `UNVERIFIED` | 无重叠、行尾动作重排 |
| M-04 | compact portrait | dark | maximum | TalkBack | 首页/error/unknown | 待采集 | 宣读状态、原因和重试 | `UNVERIFIED` | 技术值不抢主文案 |
| M-05 | compact portrait | light | default | touch | App/loading candidate | 待采集 | 等待列表完成、搜索 | `UNVERIFIED` | 需先批准并实现逐页 loading 状态；不属于首批 UI-only |
| M-06 | compact portrait | light | default | touch | App/empty search | 待采集 | 清除筛选 | `UNVERIFIED` | 明确无匹配而非无安装 |
| M-07 | compact portrait | dark | large | TalkBack | App/list | `1fc2831` 用户浅色截图 | 收藏、打开详情、返回 | `RETEST REQUIRED` | 单个 grouped surface 已实现；修正版要求筛选/搜索为一致工具镜片，收藏星标保持轻量且行高不跳动 |
| M-08 | compact portrait | light | default | touch | App detail/MAIN | 待采集 | 打开切换确认后取消 | `UNVERIFIED` | 来源/目标/保护/后果 |
| M-09 | compact portrait | light | default | touch | App detail/CLONE | 待采集 | 打开还原确认后取消 | `UNVERIFIED` | “还原主数据”精确文案 |
| M-10 | compact portrait | dark | large | touch | App detail/UNKNOWN | 待采集 | 检查状态 | `UNVERIFIED` | 禁止猜测切换动作 |
| M-11 | compact portrait | light | maximum | TalkBack | App detail/install modes | 待采集 | 浏览三个安装工具 | `UNVERIFIED` | 三个模式差异可理解 |
| M-12 | compact portrait | light | default | touch | Data/default/all classes | 待采集 | 打开 active/passive 详情；验证 clone rollback 行内恢复与当前失效详情路由 | `UNVERIFIED` | 当前 clone rollback 会进入详情，但因解析缺失显示“备份不存在”；修复需独立批准 |
| M-13 | compact portrait | dark | large | touch | Data/empty | 待采集 | 返回 App/建立快照入口 | `UNVERIFIED` | 不嵌套空卡片 |
| M-14 | compact portrait | light | default | touch | Active/passive detail/delete confirm | 待采集 | 打开删除确认后取消 | `UNVERIFIED` | 当前不提供 clone rollback 删除 |
| M-15 | compact portrait | light | default | touch | History/default | 待采集 | 将记录包名设为当前选中 App | `UNVERIFIED` | 不误写成自动打开详情；中文任务状态、耗时 |
| M-16 | compact portrait | dark | maximum | TalkBack | History/empty/error | 待采集 | 宣读重试与错误 | `UNVERIFIED` | 无截断关键信息 |
| M-17 | compact portrait | light | default | touch | Settings/default | 待采集 | 修改开关但取消/保存 | `UNVERIFIED` | 分组、字段错误关联 |
| M-18 | compact portrait | dark | large | TalkBack | Settings/ownership anomaly | 待采集 | 扫描、打开修复确认 | `UNVERIFIED` | 报告路径与设置匹配 |
| M-19 | compact portrait | light | default | touch | Settings/reset stage 1/2 | 待采集 | 两次确认后均取消 | `UNVERIFIED` | 危险层级明显 |
| M-20 | compact portrait | light | default | touch | Diagnostics/ready | 待采集 | 重新检测、查看已显示的技术值 | `UNVERIFIED` | 结论优先 |
| M-21 | compact portrait | dark | large | TalkBack | Diagnostics/query failure | 待采集 | 重试、查看失败技术值 | `UNVERIFIED` | UNKNOWN 不显示 READY；不新增复制/导出动作 |
| M-22 | compact portrait | light | default | touch | Data/stale workspace index candidate | 待采集 | 尝试恢复/删除后重新检测 | `UNVERIFIED` | 需先批准 freshness 状态合同；不属于首批 UI-only |
| M-23 | compact portrait | dark | large | TalkBack | Task/permission capture denied | 待采集 | 打开 `SUCCESS_WITH_WARNINGS` 明细 | `UNVERIFIED` | 文件完成与权限跳过同时可理解 |
| M-24 | compact portrait | light | maximum | TalkBack | Settings/long zh-CN copy | 待采集 | 浏览强制更新分数据和复用备份说明 | `UNVERIFIED` | 长文案不缩字、不遮挡控件 |

## 2. 任务状态矩阵

| ID | State | Required capture | Interaction | Result |
| --- | --- | --- | --- | --- |
| T-01 | `ACCEPTED` | 请求已接收、等待执行 | 返回页面后任务仍可见 | `UNVERIFIED` |
| T-02 | `RUNNING` determinate candidate (`N/A` in current state contract) | 仅在未来提供可信实时分母后展示百分比、文件/字节、阶段、耗时 | 页面切换不丢状态 | `UNVERIFIED` |
| T-03 | `RUNNING` staged | 第 n/m 阶段，无伪百分比 | 通知和 App 文案一致 | `UNVERIFIED` |
| T-04 | `SUCCESS` | 完成摘要 | 相关局部数据刷新 | `UNVERIFIED` |
| T-05 | `SUCCESS_WITH_WARNINGS` | 数据完成 + 权限/AppOps 警告 | 打开警告明细 | `UNVERIFIED` |
| T-06 | `AUTO_ROLLING_BACK` | 高优先级回滚状态 | 冲突动作禁用 | `UNVERIFIED` |
| T-07 | `ROLLED_BACK` | 未完成但已恢复操作前状态 | 查看失败阶段 | `UNVERIFIED` |
| T-08 | `FAILED` | 失败阶段、原因、下一步 | 安全重试路径 | `UNVERIFIED` |
| T-09 | `FAILED_FATAL` | 严重失败警示 | 进入诊断 | `UNVERIFIED` |
| T-10 | `INTERRUPTED` | 中断与状态待检查 | 检查后重试 | `UNVERIFIED` |

## 3. Adaptive layout matrix

| ID | Window | Direction | Screen | Required evidence | Result |
| --- | --- | --- | --- | --- | --- |
| A-01 | compact portrait | A2 | 5-destination floating glass tab bar | 五个顶级入口可达；诊断从设置可达；选中镜片移动正确；滚动内容经过底栏时产生真实背景采样 | `UNVERIFIED` |
| A-02 | compact landscape | A | App list/detail | inset、键盘、内容不遮挡 | `UNVERIFIED` |
| A-03 | medium | A | rail + list/detail | 选择/返回/状态保持 | `UNVERIFIED` |
| A-04 | expanded | A | drawer + 3 pane | pane 尺寸稳定、无超大空白 | `UNVERIFIED` |
| A-05 | expanded | A/B | keyboard-only + pointer focus | Tab/Shift+Tab 遍历导航、列表、对话框，focus 始终可见 | `UNVERIFIED` |
| A-06 | compact/expanded | A/B | reduced motion | 关闭系统动画后打开导航、详情和确认框，信息与焦点不丢失 | `UNVERIFIED` |
| B-01 | compact portrait | B | 4-destination nav | 数据/诊断新入口可发现 | `UNVERIFIED` |
| B-02 | medium | B | App list/workspace | App 切换和详情状态保持 | `UNVERIFIED` |
| B-03 | expanded | B | 3 pane workspace | 任务检查器持续可见 | `UNVERIFIED` |

方向已固定为 A2；B 仅保留历史设计记录，不进入本轮验收。

## 4. Launcher module matrix

| ID | Window/theme | Text scale/input | Screen/state | Interaction | Result |
| --- | --- | --- | --- | --- | --- |
| L-01 | compact light | default/touch | 状态/readiness summary | 刷新、进入 App | `UNVERIFIED` |
| L-02 | compact dark | large/TalkBack | 状态/hook error | 宣读检查项和修复入口 | `UNVERIFIED` |
| L-03 | compact light | maximum/touch | App/loading/list | 筛选、选择、保存 | `UNVERIFIED` |
| L-04 | landscape light | large/pointer | App/list | 滚动和保存不遮挡 | `UNVERIFIED` |
| L-05 | compact dark | large/TalkBack | 控制/default | 开关状态与说明 | `UNVERIFIED` |
| L-06 | compact light | default/touch | 日志/empty | 刷新 | `UNVERIFIED` |
| L-07 | compact dark | large/TalkBack | 日志/error/content | 筛选、复制、清理确认 | `UNVERIFIED` |
| L-08 | compact light/dark | large/TalkBack | 状态/CONTROL permission denied | 宣读缺失权限、影响和可执行检查 | `UNVERIFIED` |

## 5. Offline applicability

- UClone 的核心 Root、工作区、模块 Relay 和跨用户操作不依赖互联网，离线状态对核心页面为 `N/A`。
- GitHub artifact 下载属于发布流程，不属于 App 内运行状态。
- 如果未来加入网络更新、云备份或在线帮助，必须新增 offline、timeout、partial-content 截图与交互测试；当前不得用本条声明这些未来功能已支持。

## 6. Device acceptance record

实现后每次验收至少记录：

```text
commit:
GitHub Actions run:
artifact SHA-256:
installed App version/code:
installed module version/code:
device/build:
window/theme/font scale:
TalkBack/reduced motion:
screenshot paths:
actions executed:
result:
remaining issues:
```

只有当前 commit 对应的固定签名 GitHub artifact 可以关闭 `UNVERIFIED`；旧 APK、旧截图或设计 mockup 均为 `PROHIBITED-AS-EVIDENCE`。

### A2 visual gate

当前 A2 必须同时满足以下五项，任一项失败都不得称为视觉重构完成：

1. 首页和 App 页使用连续分组列表，不再为每个 App 生成独立卡片。
2. 情境主动作使用短胶囊，普通工具保持行尾文字动作，不形成按钮墙。
3. Compact 底栏为单层半透明导航 chrome，选中态只形成 icon 镜片，不出现 glass-on-glass。
4. 深色模式下高光边界可见但不过亮，内容面保持不透明和稳定对比。
5. 1.3x 及 2.0x 字体下，五个导航标签、长中文动作和 App 状态均不重叠、不裁切关键含义。

### Liquid Glass runtime gate

1. 首页、App、App 详情、数据、历史、设置、诊断分别采集浅色与深色截图。
2. 字体比例覆盖 1.0、1.3、2.0；状态覆盖默认、空、运行、警告、失败和禁用。
3. 列表滚动经过底栏时必须显示内容驱动的模糊与折射，不能是固定白色托盘。
4. 连续滚动和切换导航不得出现 RenderThread 崩溃、闪黑、玻璃错位或超过 500ms 的可见冻结。
5. 相比基线 `18b0310`，卡顿帧比例不得增加超过 5 个百分点。
6. TalkBack 阅读顺序、按钮名称、开关值和危险动作后果逐页核验。
