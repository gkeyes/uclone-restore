# UClone Premium UI Visual Contract

## Context

- Status: `DRAFT / UNAPPROVED`
- Platform/framework: Android, Jetpack Compose Material 3 + Android View
- Locale: zh-CN
- Product domain: Root / multi-user system utility
- Selected official source IDs: see `official-source-evidence.md`
- Project truth: `需求.md`, `docs/ARCHITECTURE.md`, `docs/INVARIANTS.md`, current production models
- Existing project design system: `DESIGN.md`, `Theme.kt`, `Components.kt`
- Approved direction: none; direction A is recommended, A/B remain `EXPERIMENTAL`

本合同在用户选择方向前只定义跨方向不可变规则和候选映射。它不是生产实现授权。

## 1. Current baseline audit

| Finding | Evidence | Classification | Contract response |
| --- | --- | --- | --- |
| 主题只定义 `lightColorScheme` | `Theme.kt` | `PROJECT-VERIFIED` | 新实现必须提供 light/dark semantic scheme |
| 颜色和组件以 `Ios*` 命名 | `Theme.kt`, `Components.kt` | `PROJECT-VERIFIED` | Android 生产代码改为产品/语义命名，不声称复制 iOS |
| 普通卡片大量使用 16dp 圆角和 glass 色 | 多个 screen/components | `PROJECT-VERIFIED` | 普通容器最大 8dp；section 优先无框架分组 |
| 文本按钮大量使用 999dp 胶囊 | `Components.kt` | `PROJECT-VERIFIED` | 命令按钮改为标准 Material 形态；胶囊仅限状态/segmented |
| 图标按钮视觉尺寸 40dp | `Components.kt` | `PROJECT-VERIFIED` | 交互触控区域至少 48dp |
| 诊断/详情连续堆叠多个全宽按钮 | 多个 screen | `PROJECT-VERIFIED` | 工具行 + 单主动作 + 危险区 |
| 底部导航 6 项 | `UCloneApp.kt` | `PROJECT-VERIFIED` | A 用 drawer/rail 保留层级；B 需审批后降为 4 项 |

### Existing design-system reconciliation

`DESIGN.md` 是当前项目设计系统的 `PROJECT-VERIFIED` 来源，但不是不可变的平台规范。本轮按权威范围处理，而不是静默丢弃：

| Existing rule | Decision | Evidence label | Reason |
| --- | --- | --- | --- |
| 冷静、可读、明确状态的系统工具气质 | 保留 | `PROJECT-VERIFIED` | 与产品风险和本轮 visual thesis 一致 |
| Android 系统字体、4dp 网格、分组列表、文字优先状态 | 保留 | `PROJECT-VERIFIED` | 与 Android 实现和可访问性一致 |
| 蓝色只用于动作/选择，状态色不作装饰 | 保留 | `PROJECT-VERIFIED` | 与 semantic color contract 一致 |
| 避免嵌套卡片、动效克制、日志使用 monospace | 保留 | `PROJECT-VERIFIED` | 与系统工具的信息效率一致 |
| 44dp 最小触控 | 由 Android 48dp 规则取代 | `DERIVED` | Android 是真实目标平台，官方 API defaults 优先 |
| 普通 group/list row 使用 Liquid Glass | 收缩为导航 chrome 候选 | `EXPERIMENTAL` | 数据面需要稳定对比，且不能复制 Apple chrome |
| 16dp 普通容器圆角与胶囊命令按钮 | 普通容器改为 0-8dp，胶囊仅用于状态/segmented | `EXPERIMENTAL` | 降低装饰感，符合本项目界面约束 |
| 仅浅色的 Apple 数值色板与 `Ios*` 命名 | 改为 Material semantic light/dark roles | `EXPERIMENTAL` | 支持 Android 深色和避免跨平台冒充 |
| Compact 行可省略长包名/路径 | 关键信息换行或进入可复制详情 | `DERIVED` | 不得隐藏危险对象和恢复标识 |

方向获批后，上述 `EXPERIMENTAL` 项才成为生产实现合同；获批前 `DESIGN.md` 和现有代码均不修改。

## 2. Decision ledger

| ID | Area | Decision | Evidence label | Source/project ID | Responsive/state variants | Acceptance test |
| --- | --- | --- | --- | --- | --- | --- |
| VC-001 | Product truth | 全部现有页面和 25 个动作必须可达 | `PROJECT-VERIFIED` | functional inventory/action matrix | 所有 window/state | 自动枚举覆盖 + 手工点击 |
| VC-002 | Navigation | Compact 不再使用 6 项底栏 | `DERIVED` | Android nav + current code | A drawer；B 4 项底栏 | 360dp 宽、最大字体无裁切 |
| VC-003 | Layout | section 为全宽内容带，不做浮动卡片套卡片 | `DERIVED` | project + Apple lists + frontend constraints | compact 单列；wide panes | 截图层级审查 |
| VC-004 | Touch | 所有可点击目标最小 48dp | `OFFICIAL-VERIFIED` | Android API defaults | touch/keyboard/TalkBack | Layout Inspector + 点击 |
| VC-005 | Theme | 使用 semantic light/dark color roles | `OFFICIAL-VERIFIED` | Android M3 + Apple color | light/dark/high contrast | 截图 + 对比检查 |
| VC-006 | Status | 状态使用 icon/shape + text + color | `OFFICIAL-VERIFIED` | accessibility/color | all statuses | 灰阶/色觉模拟 + TalkBack |
| VC-007 | Typography | 使用有限 Material type roles，系统字体，支持 font scale reflow | `DERIVED` | Android + Apple typography | default/large/max | 长中文和最大字体截图 |
| VC-008 | Actions | 每个操作组仅一个突出主动作 | `DERIVED` | Apple buttons + product risk | enabled/disabled/busy | 页面动作层级审查 |
| VC-009 | Destructive | 删除/重置独立危险区并确认后果 | `DERIVED` | action matrix + alerts | default/confirm/error | 实际打开确认框 |
| VC-010 | Progress | 当前只展示阶段/步骤；未来有可信实时分母才显示百分比 | `DERIVED` | `TaskProgress` 当前无可靠实时总量 | accepted/running/rollback | fixture/真机任务截图；遥测扩展需独立批准 |
| VC-011 | Content | 中文主文案，技术原值作为可展开次级信息 | `PROJECT-VERIFIED` | product truth | normal/error/diagnostic | 内容审查 + TalkBack |
| VC-012 | Material | 半透明仅限导航 chrome，数据面不透明 | `EXPERIMENTAL` | direction A/B | light/dark/edge-to-edge | 真机截图，检查对比与性能 |
| VC-013 | Motion | 平台默认短过渡；reduced motion 下移除非必要位移 | `DERIVED` | platform guidance | normal/reduced | 开发者选项/系统设置测试 |
| VC-014 | Module | 模块保留 Android View，不因视觉重构换框架 | `PROJECT-VERIFIED` | existing architecture | all module pages | 模块页面逐页截图 |

## 3. Reading order

### Standard page

1. Top app bar：页面身份和全局任务/导航。
2. 状态摘要：能否执行、当前数据/系统状态。
3. 当前情境主动作。
4. 主要内容或配置列表。
5. 次级工具。
6. 危险动作。
7. 技术原值/日志。

### App detail

1. App 身份、两侧安装和风险。
2. `MAIN` / `CLONE` / `UNKNOWN`。
3. 当前状态对应主动作。
4. 数据来源和备份。
5. 数据范围与安装/诊断工具。
6. 危险区。

### Task detail

1. App + 业务动作。
2. 终态或当前阶段。
3. 阶段、文件、字节、耗时。
4. 警告/失败与下一步。
5. request ID、日志路径等技术信息。

## 4. Responsive transformations

| Window class | Direction A | Direction B | Shared behavior |
| --- | --- | --- | --- |
| Compact | Modal drawer + 单列页面 | 4 项 nav bar + App workspace tabs | 行尾动作在大字体时移到下一行；不横向压缩文字 |
| Medium | Navigation rail + list/detail | Rail + App list/detail | 详情保持稳定宽度，任务可作为 overlay/bottom sheet |
| Expanded | Permanent drawer + 2/3 pane | App list/workspace/inspector 3 pane | 不放大字体模拟桌面；增加并列信息 |

不在文档中硬编码 window breakpoint；实现使用项目依赖版本提供的 Android window size class。

## 5. Layout tokens

| Token | Value | Use |
| --- | ---: | --- |
| `space-1` | 4dp | icon/text 微间距 |
| `space-2` | 8dp | 行内间距、紧凑 section |
| `space-3` | 12dp | list item 内部间距 |
| `space-4` | 16dp | 页面水平 padding、标准 section |
| `space-6` | 24dp | 主要 section 分隔 |
| `space-8` | 32dp | 危险区或大段分隔 |
| ordinary radius | 0-8dp | cards, inputs, rows |
| status/segmented radius | full | 仅状态 badge、segmented、圆形 icon button |
| minimum touch | 48dp | 所有 interactive target |

- 使用稳定的 `minHeight`、grid/pane 宽度和 icon slot，避免状态变化造成布局跳动。
- 文本不通过 viewport 宽度缩放字号。
- letter spacing 保持 0，除非平台组件默认值明确要求。

## 6. Typography roles

| Role | Material mapping | Use | Localization behavior |
| --- | --- | --- | --- |
| Page title | `headlineMedium`/`titleLarge` | 页面身份 | Compact 可换行，不用超大 hero |
| Section title | `titleMedium` | 分组标题 | 中文保持短语 |
| Row title | `bodyLarge`/`titleSmall` | App、工具、设置名称 | 最多按内容自然换行 |
| Primary value | `bodyLarge` + Medium/Semibold | 状态与关键值 | 不使用轻字重 |
| Supporting | `bodyMedium` | 解释、来源、目标 | 长中文允许多行 |
| Technical | `bodySmall`, monospace only where useful | UID、request ID、路径 | 支持选择/复制；不挤占主层 |
| Action label | `labelLarge` | 明确命令 | 动词 + 对象，避免含糊 |

- 实现使用 Android 系统字体，不嵌入 SF。
- font scale 增大时优先保留主标题、状态、风险和动作；次级技术信息可移到下一行/展开区。
- 不通过截断隐藏包名、rollback ID 或危险后果；必要时换行或提供可复制详情。

## 7. Semantic color contract

| Role | Meaning |
| --- | --- |
| `primary` | 当前页面的唯一主动作与选中导航 |
| `surface` / `surfaceContainer*` | 内容层级，不为每个 section 随意着色 |
| `onSurface` / variants | 主/次文字 |
| `success`（项目扩展语义） | 已验证成功/正常 |
| `warning`（项目扩展语义） | 部分完成、需要注意、运行中关键状态 |
| `error` | 失败、删除、重置、高风险后果 |
| `outline` | 分组边界和 divider |

- 保留 UClone 蓝作为 accent，但界面不得由单一蓝色家族统治。
- 每个 semantic role 提供 light/dark 变体；不硬编码 Apple system color 值。
- 绿色/橙色/红色不用于纯装饰。
- 状态必须同时有文字和 icon/shape。

## 8. Component contract

| Component | Official/project primitive | Variants | States | Accessibility behavior | Customization boundary |
| --- | --- | --- | --- | --- | --- |
| App shell | `Scaffold`, drawer/rail/nav bar | A/B + window class | default/task active | 当前目的地有语义；返回可预测 | 不手绘系统栏 |
| Top app bar | Material 3 app bar | root/detail | scrolled/default | 标题、导航和 action 描述完整 | chrome 可轻量材质，不遮内容 |
| Health summary | project composable/View group | ready/warning/error/unknown | 当前可空环境；loading/stale 为候选 | 一次读出结论和原因 | 不只显示绿点；新增状态字段需独立任务 |
| Task panel | progress indicators + project rows | staged/rollback；determinate 为后续能力 | all task statuses | 宣读动作、阶段和已有耗时 | 当前不伪造百分比或实时文件总量 |
| State badge | icon + text | MAIN/CLONE/UNKNOWN | normal/stale | 完整 stateDescription | 仅 badge 可 pill |
| App row | Material list item | compact/detail | selected/favorite/busy | App 名、安装和数据状态合并语义 | 图标 slot 稳定，按钮触控 48dp |
| Tool row | ListItem + trailing icon/button | normal/warning/danger | enabled/disabled/running | action label + disabled reason | 不做一排全宽胶囊按钮 |
| Setting row | ListItem + Switch/input/menu | toggle/text/choice | valid/error/disabled | label、value、error 关联 | 不在 row 内塞多级 card |
| Backup row | project row | active/passive/clone rollback | valid/stale/selected | 来源、时间、大小、动作 | 来源标签固定且不靠颜色 |
| Confirmation | `AlertDialog` / intentional sheet | overwrite/delete/reset | default/busy/error | focus 标题，动作顺序稳定 | 必须包含来源、目标、后果 |
| Empty/error state | text + icon + action | empty；error/stale 需数据支持 | page-specific | 原因与恢复动作一次读出 | 纯 UI 阶段只渲染现有状态；不凭空增加状态源 |
| Log view | text/list | 主 App 任务结果/日志路径；模块 recent/full | loading/empty/error | 模块现有复制按钮有描述；保持阅读顺序 | 主 App 不新增打开、复制或导出；模块保留已有复制能力 |
| Module segmented nav | Android View primitives | 4-page A / 3-page B | selected/focus | 选中状态、页面数 | B 需要移动功能审批 |

## 9. Icon contract

- 主 App 使用现有 Material Icons 或 Material Symbols；不手绘已有标准图标。
- 模块使用 Android 平台/现有 drawable 资源。
- icon-only 按钮必须有 `contentDescription` 或 View content description。
- 不熟悉图标配文字；熟悉导航/返回/刷新可仅图标但需语义标签。
- 不打包 SF Symbols。

## 10. States and content freshness

- 默认、empty、busy、partial success、rollback、fatal 使用现有状态提供显式样式；逐页 loading/error/stale 在状态模型获批并实现后补齐。
- `UiState.message` 是短期反馈，不得成为真实 App 状态来源。
- workspace index 读取失败时，覆盖/删除动作不能继续使用未验证的旧索引。
- ownership report 与 rootDir/userIds 不匹配时显示过期并禁止修复。
- 当前任务和任务历史分别表达实时状态与持久记录。

## 11. Risk and destructive safeguards

确认框固定结构：

```text
标题：动词 + 对象
来源：哪一份数据
目标：哪个用户/目录中的 App
保护：本次操作前会创建什么保护
后果：覆盖、删除或不可逆范围
主按钮：精确动作
取消：保留当前状态
```

- 删除主动快照、删除指定被动备份、清日志、重置工作区必须使用不同文案。
- 推送必须写明“user0 当前数据 -> user10，会覆盖分身 App”。
- 还原必须写明“MAIN 返回点 -> user0”，不使用孤立的“恢复到主系统”。
- 系统 App、高风险 App、UClone 自身继续遵守现有限制。

## 12. Interaction and accessibility

- Touch：最小 48dp，单手紧凑布局不把两个高风险动作并排。
- Keyboard/pointer：wide layout 提供可见 focus；Tab 顺序遵循阅读顺序。
- TalkBack：状态、按钮、开关值、列表位置和错误原因可读；装饰 icon 不重复朗读。
- Text scale：default、large、最大可访问字体都不得重叠或遮挡后续内容。
- Contrast：light/dark/高对比语义角色逐屏验证。
- Reduced motion：无必要位移、闪烁或自动滚动。
- Localization：中文长标签不缩小字号，改用换行、行布局重排或菜单。

## 13. SwiftUI semantic references to actual components

| Reference | Compose | Android View module |
| --- | --- | --- |
| `NavigationSplitView` | adaptive scaffold/list-detail pane | responsive Linear/Frame layout as needed |
| `List` / `Section` | `LazyColumn`, `ListItem`, headers | `RecyclerView`/existing programmatic rows |
| `ProgressView` | Material progress indicators | platform progress indicators |
| `Toggle` | Material `Switch` | `SwitchMaterial`/platform switch available in module |
| `Alert` / `confirmationDialog` | `AlertDialog` / sheet | `AlertDialog` |
| `ToolbarItem` | `TopAppBar` action | toolbar action |

该表只映射语义，不复制 Apple chrome。

## 14. Implementation slices after approval

1. Theme and semantic components：light/dark、48dp、语义状态、基础 row；不改导航。
2. Approved navigation shell：A 或 B 单独提交。
3. Main screens：按首页、App、数据、历史、设置、诊断逐页迁移。
4. Launcher module：独立提交，不和主 App 页面混改。
5. Accessibility/large text/adaptive fixes。
6. Screenshot matrix 与真机交互验收。

每个切片只改 UI，不夹带 Root、状态判定、协议、依赖升级或发布版本变更。逐页 loading/error/stale、可靠实时百分比、审计包导出或新的备份详情类型都需要独立数据/行为契约，不得以“视觉完善”为名加入 UI 切片。

## 15. Unverified items

| Item | Why unverified | Risk | Required follow-up |
| --- | --- | --- | --- |
| A/B 最终方向 | 用户尚未选择 | 无法确定导航实现 | 明确批准 A、B 或 A+B refinement |
| Material Adaptive API | 未在本阶段改依赖/编译 | API 可能与当前版本不同 | 实现前核对 Gradle 依赖和官方文档 |
| Light/dark color values | 未渲染 | 对比/品牌平衡未知 | 真机截图与对比检查 |
| Chrome translucency | 未实现 | 性能/可读性风险 | 低端设备和内容滚动截图 |
| 大字体/TalkBack | 未实现 | 可访问性风险 | 真机逐页测试 |
| Module responsive layout | 当前为 programmatic View | 大字体/横屏风险 | 模块真机截图与操作 |
| Compact/expanded behavior | 无当前渲染证据 | pane/导航可能不适配 | 手机、横屏/平板或模拟器验证 |
| 逐页 loading/error/stale | 当前 `UiState` 无对应字段 | 设计可能误报旧数据新鲜度 | 独立状态模型方案、回归测试和批准 |
| 确定百分比 | 当前 `TaskProgress` 无可信实时分母 | 伪造进度 | 独立遥测契约和真机数据验证 |

在这些项目验证前，禁止使用“完成”“Apple-quality”“HIG compliant”或“无障碍合规”等结论。
