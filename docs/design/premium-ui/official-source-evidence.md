# UClone Premium UI 官方证据账本

- 核验日期：2026-07-13
- Live web：可用
- 本地 Apple 官方预览库：可用，仅选择 1 张平台预览
- 完整 Apple UI Kit：未下载到项目，不作为实现尺寸来源
- Android 目标实现：Jetpack Compose Material 3 + 原生 Android View

## 1. 权威范围

| 问题 | 权威来源 | 证据等级 | 边界 |
| --- | --- | --- | --- |
| 有哪些功能、状态和风险 | 当前项目需求、代码、用户批准 | `PROJECT-VERIFIED` | 不得由视觉参考覆盖 |
| Android 组件、触控、导航和自适应行为 | Android Developers、Material 3 | `OFFICIAL-VERIFIED` | 生产实现的主要平台依据 |
| 信息层级、列表分组、动作克制、可读性参考 | Apple HIG / Apple Design Resources | `OFFICIAL-VERIFIED` | 只作参考，不产生 Android HIG 合规声明 |
| UClone 的视觉方向 | 本项目批准的视觉契约 | `EXPERIMENTAL` | 尚未批准 |
| 运行效果 | 当前分支真实 APK 截图与真机操作 | `UNVERIFIED` | 本阶段无生产 UI 改动 |

## 2. 已实际打开或本地核验的来源

| Decision ID | Source ID | Evidence label | 来源 | 管辖范围 | 对 UClone 的应用 | 限制 |
| --- | --- | --- | --- | --- | --- | --- |
| D-001 | `apple-design-resources` | `OFFICIAL-VERIFIED` | https://developer.apple.com/design/resources/ | 官方 UI Kit、模板与工具入口 | 选择 iOS/iPadOS 平台预览用于层级/分组观察 | 缩略预览不提供精确尺寸或完整状态 |
| D-002 | `apple-ios-ipados-ui-kit` | `OFFICIAL-VERIFIED` | 本地 manifest 对应 Apple Design Resources | 平台 chrome、列表/操作分组、控件家族 | 只抽取“内容优先、分组清楚、系统 chrome 克制” | 不复制 iPhone bezel、Home Screen、键盘或 SwiftUI 几何 |
| D-003 | `apple-hig-tab-bars` | `OFFICIAL-VERIFIED` | https://developer.apple.com/design/human-interface-guidelines/tab-bars | Apple 顶层导航 | 支持“顶层入口应少而清楚”的方向判断 | Android 生产导航仍遵循 Android 官方组件 |
| D-004 | `apple-hig-sidebars` | `OFFICIAL-VERIFIED` | https://developer.apple.com/design/human-interface-guidelines/sidebars | Apple 大屏层级导航 | 参考宽屏时让高层级入口常驻 | 不直接复制 iPad sidebar 到手机 |
| D-005 | `apple-hig-lists-tables` | `OFFICIAL-VERIFIED` | https://developer.apple.com/design/human-interface-guidelines/lists-and-tables | 列表、分组与 disclosure | 将设置、诊断和工具动作改为可扫描分组行 | 不推导 Android 行高或圆角 |
| D-006 | `apple-hig-settings` | `OFFICIAL-VERIFIED` | https://developer.apple.com/design/human-interface-guidelines/settings | 设置组织与默认值 | 减少说明堆叠；任务专属选项尽量放在任务上下文 | 不批准删除现有设置 |
| D-007 | `apple-hig-progress` | `OFFICIAL-VERIFIED` | https://developer.apple.com/design/human-interface-guidelines/progress-indicators | 进度和取消表达 | 有可信分母才显示百分比；阶段变化立即可见 | 当前后端是否提供分母由项目数据决定 |
| D-008 | `apple-hig-buttons` | `OFFICIAL-VERIFIED` | https://developer.apple.com/design/human-interface-guidelines/buttons | 动作层级与可识别性 | 每个区域只突出最可能的主动作 | Apple 44pt 不用于 Android；Android 使用 48dp 最小触控 |
| D-009 | `apple-hig-alerts` | `OFFICIAL-VERIFIED` | https://developer.apple.com/design/human-interface-guidelines/alerts | 高风险确认 | 仅覆盖、删除、重置等少见高风险操作使用强确认 | 不把所有次级选择都做成阻塞 alert |
| D-010 | `apple-hig-typography` | `OFFICIAL-VERIFIED` | https://developer.apple.com/design/human-interface-guidelines/typography | 可读性、层级和 Dynamic Type | 使用有限字体角色、避免细字重、长中文可换行 | Android 使用系统字体与 Compose typography，不嵌入 SF |
| D-011 | `apple-hig-color` | `OFFICIAL-VERIFIED` | https://developer.apple.com/design/human-interface-guidelines/color | 语义颜色、深色/高对比、非纯颜色表达 | 颜色按语义角色；状态同时用文字与图形 | 不硬编码 Apple 系统颜色值 |
| D-012 | `apple-hig-accessibility` | `OFFICIAL-VERIFIED` | https://developer.apple.com/design/human-interface-guidelines/accessibility | 大文本、对比和辅助技术 | 大文本重排、状态不只依靠颜色、真实辅助技术验收 | Apple 的 pt 指标不直接移植 Android |
| D-013 | `android-navigation-bar` | `OFFICIAL-VERIFIED` | https://developer.android.com/develop/ui/compose/components/navigation-bar | Compose 紧凑窗口顶层导航 | 当前 6 项底部导航属于需要重构的问题；导航栏适合 3-5 个目的地 | 不授权在未批准时移动页面 |
| D-014 | `android-adaptive` | `OFFICIAL-VERIFIED` | https://developer.android.com/develop/ui/compose/layouts/adaptive/get-started-with-adaptive-apps | window size、自适应导航和 pane | 候选实现使用 `NavigationSuiteScaffold`、`ListDetailPaneScaffold` | 需要实现后按实际依赖版本校验 API |
| D-015 | `android-compose-accessibility` | `OFFICIAL-VERIFIED` | https://developer.android.com/develop/ui/compose/accessibility | Compose semantics 和辅助技术 | 状态/动作提供语义标签、内容顺序和合并策略 | 必须通过 TalkBack 真机验证 |
| D-016 | `android-compose-api-defaults` | `OFFICIAL-VERIFIED` | https://developer.android.com/develop/ui/compose/accessibility/api-defaults | 可访问组件默认行为与触控目标 | Android 交互控件最小触控目标为 48dp | 视觉图标可小于触控区域，但语义区域不能缩水 |
| D-017 | `android-edge-to-edge` | `OFFICIAL-VERIFIED` | https://developer.android.com/develop/ui/views/layout/edge-to-edge | 系统栏和 inset | 实现需处理 status/navigation bar inset 和横屏 | 当前分支尚未渲染验证 |
| D-018 | `material3-color` | `OFFICIAL-VERIFIED` | https://m3.material.io/styles/color/overview | Android 语义色角色 | 用 Material color roles 建 light/dark contract | 不做单一蓝色主题的全屏灌色 |
| D-019 | `material3-components` | `OFFICIAL-VERIFIED` | https://m3.material.io/components | 官方组件使用边界 | 优先复用 App bars、lists、dialogs、switch、progress | 不手绘已有标准组件 |
| D-020 | `material3-typography` | `OFFICIAL-VERIFIED` | https://m3.material.io/styles/typography/overview | Android 字体角色 | 映射 display/title/body/label，不按 viewport 缩放字号 | 中文长文案以换行和布局重排处理 |
| D-021 | `material-symbols-guide` | `OFFICIAL-VERIFIED` | https://developers.google.com/fonts/docs/material_symbols | Android 图标来源 | 主 App 保持 Material Icons/Symbols 语义一致 | Apple SF Symbols 不打包到 Android |
| D-022 | `project-design-system` | `PROJECT-VERIFIED` | `DESIGN.md`, `Theme.kt`, `Components.kt` | 当前产品视觉、token 与组件基线 | 保留冷静、状态明确、4dp 网格、系统字体和分组列表 | 44dp、light-only、`Ios*` 命名、全内容 glass 与 Android 官方/本轮约束冲突，见 visual contract |

## 3. 已选择但未作为决策证据的来源

| Source ID | 标签 | 原因 |
| --- | --- | --- |
| `apple-developer-terms` | `UNVERIFIED` | 选择器返回，但本阶段未复制或分发 Apple 资产；需要实际打包资产时再核验条款 |
| `apple-sf-symbols` | `UNVERIFIED` | 当前目标是 Android，SF Symbols 不作为生产图标库 |
| `wcag2ict` | `UNVERIFIED` | 未在本轮打开，不声明 WCAG/WCAG2ICT 合规 |
| `material3-motion` | `UNVERIFIED` | 方向阶段只规定减少无意义动画，具体 motion 待实现前打开并核验 |

## 4. 冲突解析

1. Apple tab bar 与 Android navigation bar 都强调有限的顶层目的地；这支持“当前 6 项底栏需要处理”，但不决定哪些功能可被移动。
2. Apple 参考提供信息层级和克制的动作表达；Android 官方组件决定触控尺寸、系统栏、导航组件和实现 API。
3. 当前项目的 6 个顶层功能均必须保留。方向 A 仅改变入口组件；方向 B 涉及移动层级，因此保持 `EXPERIMENTAL`。
4. Apple 44pt 与 Android 48dp 冲突时，以 Android 48dp 为实现标准。
5. SwiftUI 仅作为用户最初指定的语义对照；真实生产框架保持 Compose/View。
6. `DESIGN.md` 的产品气质与信息原则继续有效；具体平台几何与颜色由 Android 官方来源和获批 visual contract 取代，冲突项不会在未批准时修改。

## 5. 资产权利与分发

- 没有把 Apple UI Kit、预览 PNG、SF 字体或 SF Symbols 复制进仓库。
- 文档只记录 asset ID、来源 URL、证据范围和不可推导范围。
- 项目现有 App 图标与截图不是本阶段新增资产。
- 如后续需要把任何第三方资产纳入 APK，必须单独建立 asset rights ledger 并核验许可。
