# UClone Premium UI Visual Contract

## Context

- Status: `LIQUID GLASS IMPLEMENTED / DEVICE VERIFICATION PENDING`
- Platform/framework: Android, Jetpack Compose Material 3 + Android View
- Locale: zh-CN
- Product domain: Root / multi-user system utility
- Selected official source IDs: see `official-source-evidence.md`
- Project truth: `需求.md`, `docs/ARCHITECTURE.md`, `docs/INVARIANTS.md`, current production models
- Existing project design system: `DESIGN.md`, `Theme.kt`, `Components.kt`
- Approved direction: Android-adapted iOS 26 grouped utility with Backdrop-based Liquid Glass chrome, approved by the user on 2026-07-13

本合同约束当前生产实现。视觉方向借鉴 iOS 的分组、层级和语义色，但真实平台仍是 Android；不得声称原生 iOS、Apple HIG 合规或使用 SF Symbols。

## 1. Current baseline audit

| Finding | Evidence | Classification | Contract response |
| --- | --- | --- | --- |
| 旧主题使用 Material 蓝灰和浅紫容器 | 真机截图、`Theme.kt` | `PROJECT-VERIFIED` | 改为中性 grouped background、纯色内容面和系统蓝强调 |
| Compact 使用居中标题、汉堡菜单和 drawer | 真机截图、`UCloneApp.kt` | `PROJECT-VERIFIED` | 改为左对齐标题和五项悬浮底部导航；诊断从设置进入 |
| 第一版 A1 仍使用描边、阴影和 18dp 大圆角卡片 | commit `6edc5d4` 真机截图 | `PROJECT-VERIFIED` | 内容面改为 12dp 无描边、无阴影的 inset grouped surface |
| 第一版 A1 仍有整行实心按钮和 Material 按钮墙 | commit `6edc5d4` 真机截图、`Components.kt` | `PROJECT-VERIFIED` | 普通命令改为行尾蓝色/红色文字；每个情境只允许一个短实心主动作 |
| 第一版 A1 底栏选中项显示大面积蓝色矩形 | commit `6edc5d4` 真机截图、`UCloneApp.kt` | `PROJECT-VERIFIED` | 选中态只使用蓝色 icon 和文字，不增加背景块或额外指示器 |
| A1 主动作仍是 12dp 方角色块 | commit `6709e8b` 真机首页截图、用户反馈 | `PROJECT-VERIFIED` | 主动作改为短胶囊；普通和危险动作继续使用文字层级，不生成按钮墙 |
| A1 App/收藏列表仍是逐项独立卡片 | commit `6709e8b` 真机首页截图、用户反馈 | `PROJECT-VERIFIED` | 改为单个 grouped surface 内的连续列表行和缩进分隔线 |
| A1 底栏材质接近不透明白色托盘 | commit `6709e8b` 真机首页截图、用户提供的 iOS 26 方向 | `PROJECT-VERIFIED` | 使用五项 Backdrop 玻璃底栏、独立选中镜片和真实背景采样 |
| A2 首页收藏行的实心蓝胶囊过重，App 页工具图标与收藏图标缺少统一层级 | commit `1fc2831` 真机截图、用户反馈 | `PROJECT-VERIFIED` | 主动作改为低饱和语义色玻璃胶囊；筛选、搜索、更多使用圆形工具镜片；列表收藏保持轻量图标 |
| 图标按钮视觉尺寸 40dp | `Components.kt` | `PROJECT-VERIFIED` | 交互触控区域至少 48dp |
| 诊断/详情连续堆叠多个全宽按钮 | 多个 screen | `PROJECT-VERIFIED` | 工具行 + 单主动作 + 危险区 |
| 六项底栏在手机上拥挤 | `UCloneApp.kt`、用户批准计划 | `PROJECT-VERIFIED` | 顶层收敛为首页、App、数据、历史、设置；诊断保留为设置二级页 |

### Existing design-system reconciliation

`DESIGN.md` 是当前项目设计系统的 `PROJECT-VERIFIED` 来源，但不是不可变的平台规范。本轮按权威范围处理，而不是静默丢弃：

| Existing rule | Decision | Evidence label | Reason |
| --- | --- | --- | --- |
| 冷静、可读、明确状态的系统工具气质 | 保留 | `PROJECT-VERIFIED` | 与产品风险和本轮 visual thesis 一致 |
| Android 系统字体、4dp 网格、分组列表、文字优先状态 | 保留 | `PROJECT-VERIFIED` | 与 Android 实现和可访问性一致 |
| 蓝色只用于动作/选择，状态色不作装饰 | 保留 | `PROJECT-VERIFIED` | 与 semantic color contract 一致 |
| 避免嵌套卡片、动效克制、日志使用 monospace | 保留 | `PROJECT-VERIFIED` | 与系统工具的信息效率一致 |
| 44dp 最小触控 | 由 Android 48dp 规则取代 | `DERIVED` | Android 是真实目标平台，官方 API defaults 优先 |
| 普通 group/list row 使用 Liquid Glass | 内容层改为不透明 grouped surface，仅导航层允许半透明 | `PROJECT-VERIFIED` | 保证数据面稳定对比，避免 glass-on-glass |
| 普通容器与命令按钮圆角 | 内容容器/输入 12dp、短命令胶囊、导航 30dp | `PROJECT-VERIFIED` | 内容仍保持克制；只有可点击命令和导航 chrome 使用连续胶囊/圆形轮廓 |
| Apple 风格色板 | 作为 UClone 产品色选择映射到 semantic light/dark roles | `PROJECT-VERIFIED` | 不以 `Ios*` 命名，不冒充原生 Apple 组件 |
| Compact 行可省略长包名/路径 | 关键信息换行或进入可复制详情 | `DERIVED` | 不得隐藏危险对象和恢复标识 |

本次批准只改变视觉表达，不批准移动、删除或新增业务功能，也不改变 Root、任务、备份、恢复和外部协议语义。

## 2. Decision ledger

| ID | Area | Decision | Evidence label | Source/project ID | Responsive/state variants | Acceptance test |
| --- | --- | --- | --- | --- | --- | --- |
| VC-001 | Product truth | 全部现有页面和 25 个动作必须可达 | `PROJECT-VERIFIED` | functional inventory/action matrix | 所有 window/state | 自动枚举覆盖 + 手工点击 |
| VC-002 | Navigation | Compact 使用 5 项等宽悬浮玻璃底栏；诊断从设置进入；详情隐藏底栏并显示返回 | `PROJECT-VERIFIED` | 用户决定 + current code | compact/详情 | 自动导航合同 + 360dp 真机大字体检查 |
| VC-003 | Layout | 页面使用 inset grouped surface，不允许 card 套 card | `PROJECT-VERIFIED` | 用户决定 + grouped utility direction | compact 单列；wide sidebar | 截图层级审查 |
| VC-004 | Touch | 所有可点击目标最小 48dp | `OFFICIAL-VERIFIED` | Android API defaults | touch/keyboard/TalkBack | Layout Inspector + 点击 |
| VC-005 | Theme | 使用 semantic light/dark color roles | `OFFICIAL-VERIFIED` | Android M3 + Apple color | light/dark/high contrast | 截图 + 对比检查 |
| VC-006 | Status | 状态使用 icon/shape + text + color | `OFFICIAL-VERIFIED` | accessibility/color | all statuses | 灰阶/色觉模拟 + TalkBack |
| VC-007 | Typography | 使用有限 Material type roles，系统字体，支持 font scale reflow | `DERIVED` | Android + Apple typography | default/large/max | 长中文和最大字体截图 |
| VC-008 | Actions | 普通工具使用行尾文字动作；每个操作组最多一个低饱和短胶囊主动作，禁止整页全宽按钮墙 | `DERIVED` | Apple buttons + product risk + `6edc5d4`/`6709e8b`/`1fc2831` 真机复盘 | enabled/disabled/busy | 页面动作层级审查 |
| VC-009 | Destructive | 删除/重置独立危险区并确认后果 | `DERIVED` | action matrix + alerts | default/confirm/error | 实际打开确认框 |
| VC-010 | Progress | 当前只展示阶段/步骤；未来有可信实时分母才显示百分比 | `DERIVED` | `TaskProgress` 当前无可靠实时总量 | accepted/running/rollback | fixture/真机任务截图；遥测扩展需独立批准 |
| VC-011 | Content | 中文主文案，技术原值作为可展开次级信息 | `PROJECT-VERIFIED` | product truth | normal/error/diagnostic | 内容审查 + TalkBack |
| VC-012 | Material | Backdrop 光学用于与捕获层同级的导航和选中镜片；捕获内容树内的工具控件与情境主动作使用等尺寸静态半透明材质，数据面保持不透明 | `PROJECT-VERIFIED` | approved direction + Backdrop 1.0.2 glass-on-glass restriction | light/dark/edge-to-edge | 模拟器渲染 + 滚动背景折射、边缘高光和性能真机检查 |
| VC-013 | Motion | 平台默认短过渡；reduced motion 下取消缩放和位移，只保留 150ms 淡入淡出 | `PROJECT-VERIFIED` | platform guidance + current code | normal/reduced | 开发者选项/系统设置测试 |
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
| Compact | 左对齐标题 + 5 项悬浮底栏 + 单列 grouped content | 诊断从设置进入；详情隐藏底栏并显示返回 | 行尾动作在大字体时移到下一行；不横向压缩文字 |
| Medium | 92dp 侧栏 + 单列/列表详情内容 | 详情保持稳定宽度 | 选中态使用浅色语义面，不使用 Material rail 指示器 |
| Expanded | 248dp 侧栏 + 内容区 | 允许后续增加并列信息但不移动功能 | 不放大字体模拟桌面 |

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
| control radius | 12dp | fields and compact content rows |
| command shape | capsule | short primary/secondary command controls only |
| grouped radius | 12dp | content cards and grouped lists |
| navigation radius | 30dp | floating compact navigation only |
| status radius | full | status badge and circular icon button |
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

- 使用系统蓝作为 accent，但界面不得由单一蓝色家族统治。
- 每个 semantic role 提供 light/dark 变体；数值是 UClone 产品 token，不声明为系统 API 返回值。
- 绿色/橙色/红色不用于纯装饰。
- 状态必须同时有文字和 icon/shape。

## 8. Component contract

| Component | Official/project primitive | Variants | States | Accessibility behavior | Customization boundary |
| --- | --- | --- | --- | --- | --- |
| App shell | `Scaffold`, Backdrop tab bar, custom sidebar | compact/medium/expanded | default/task active | 当前目的地有语义；返回可预测 | 五项顶级入口；诊断功能不删除，只下沉到设置 |
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

## 14. Implemented slices

1. `2375759`：Backdrop 1.0.2、共享背景采样和 Liquid Glass 基础。
2. `91aab0f`：五项底栏、选中镜片、设置内诊断与返回归属。
3. `771acf0`：连续分组页面、紧凑列表和行尾操作层级。
4. 当前提交：组件语义测试、导航合同、循环 Backdrop 隔离、玻璃边缘抛光和视觉 QA 合同。

每个切片只改 UI，不夹带 Root、状态判定、协议、依赖升级或发布版本变更。逐页 loading/error/stale、可靠实时百分比、审计包导出或新的备份详情类型都需要独立数据/行为契约，不得以“视觉完善”为名加入 UI 切片。

## 15. Unverified items

| Item | Why unverified | Risk | Required follow-up |
| --- | --- | --- | --- |
| 当前 Liquid Glass 真机还原度 | 当前分支尚未安装，旧截图不对应本实现 | 模糊、折射、边缘高光和信息密度仍可能在设备上偏离合同 | 用户授权后安装当前 GitHub 固定签名 APK 并逐页截图 |
| Light/dark color values | 未渲染 | 对比/品牌平衡未知 | 真机截图与对比检查 |
| Chrome translucency | 底栏与捕获层为同级，使用 Backdrop 1.0.2 的背景捕获、blur、lens 和 vibrancy；捕获内容树不读取同一 Backdrop | 真机对比和与滚动内容融合程度未知 | GitHub 模拟器先执行渲染测试；固定产物滚动录像和截图继续检查闪黑、错位和卡顿 |
| 大字体/TalkBack | 48dp、危险/禁用和导航语义由 GitHub 模拟器执行自动合同，设备阅读顺序未验证 | 可访问性风险 | font scale 1.0/1.3/2.0 和 TalkBack 逐页测试 |
| Module responsive layout | 当前为 programmatic View | 大字体/横屏风险 | 模块真机截图与操作 |
| Compact/expanded behavior | 无当前渲染证据 | pane/导航可能不适配 | 手机、横屏/平板或模拟器验证 |
| 逐页 loading/error/stale | 当前 `UiState` 无对应字段 | 设计可能误报旧数据新鲜度 | 独立状态模型方案、回归测试和批准 |
| 确定百分比 | 当前 `TaskProgress` 无可信实时分母 | 伪造进度 | 独立遥测契约和真机数据验证 |

在这些项目验证前，禁止使用“完成”“Apple-quality”“HIG compliant”或“无障碍合规”等结论。
