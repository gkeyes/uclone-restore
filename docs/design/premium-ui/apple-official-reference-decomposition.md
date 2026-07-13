# Apple Official Reference Decomposition

## Task

- Product: UClone Restore / UClone Module
- Actual target: Android phone and adaptive Android layouts
- Reference platform: iOS / iPadOS
- Actual framework: Jetpack Compose Material 3 + Android View
- Reference framework vocabulary: SwiftUI
- Screen or flow: settings, diagnostics, system utility navigation and task states
- Design question: 如何在不改变 UClone 数据语义的前提下，获得更清楚、克制、可信的系统工具界面

## Selector execution

```bash
python3 scripts/select_apple_assets.py \
  --platform ios \
  --task settings \
  --task diagnostics \
  --limit 6
```

选择器只返回 1 个匹配的平台资产。没有为了凑足 6 张而加载无关技术素材，也没有一次性加载 62 张官方预览。

## Selected official assets

### Asset 1

- Asset ID: `apple-ios-ipados-ui-kit`
- File: `/Users/jianchen/.codex/skills/premium-product-design/assets/official/apple/previews/platform/01-ios-ipados-ui-kit.png`
- Source: https://developer.apple.com/design/resources/
- Evidence: `OFFICIAL-VERIFIED`
- Official scope: iOS/iPadOS UI Kit 的平台视觉方向；层级、分组、系统 chrome、密度与控件家族
- Why selected: 设置和诊断属于文本、状态、列表与动作密集型界面，需要平台级分组与层级参考
- Visible principles:
  - 内容区域与系统 chrome 有明确层级。
  - 列表/操作行依靠间距、分组和排版建立秩序，不依赖大量装饰。
  - 关键动作易识别，次级动作不与主动作竞争。
  - 浅色与深色控件都保持清晰前景/背景对比。
- Product translation:
  - UClone 设置、诊断和工具区采用分组行，而不是连续的大型实心按钮。
  - 普通状态用标题、值和次级技术信息组成稳定三层阅读顺序。
  - 导航和顶部栏可使用克制的半透明/材质，但数据内容保持实色可读面。
  - 危险动作单独分区并显示后果。
- Do not copy:
  - iPhone/iPad 产品 bezel。
  - Apple Home Screen、键盘、分享面板的品牌外观。
  - SwiftUI 组件几何、Apple 字体、SF Symbols 或 Apple 系统颜色值。
  - 缩略图中的精确间距、圆角、阴影和模糊参数。
- Missing evidence:
  - Android 紧凑/中等/展开窗口的真实布局。
  - UClone 当前分支的深色、大字体、错误和 TalkBack 截图。
  - 模块原生 View 在新版方向下的实现效果。
- Live HIG pages still required: 已打开 Layout 相关组件、Typography、Color、Buttons、Alerts、Accessibility、Tab bars、Sidebars、Lists and tables、Settings、Progress indicators。

## Decisions

| Decision | Evidence type | Source / asset ID | Project translation | Status |
| --- | --- | --- | --- | --- |
| 信息内容优先于装饰层 | `DERIVED` | `apple-ios-ipados-ui-kit` + UClone 高风险状态 | 数据面不使用重玻璃和渐变背景 | `DERIVED` |
| 设置和诊断使用可扫描的分组行 | `DERIVED` | `apple-ios-ipados-ui-kit`, Apple Lists/Settings | 标题、值、说明、行尾控件形成稳定节奏 | `DERIVED` |
| 每组只突出一个主动作 | `DERIVED` | Apple Buttons/Alerts + action matrix | 其余工具进入行尾动作或更多菜单 | `DERIVED` |
| 导航材质与内容面分离 | `EXPERIMENTAL` | 平台预览与 Apple Color | 半透明只用于 chrome，内容面保持可读 | `EXPERIMENTAL` |
| Android 采用 48dp 触控目标 | `DERIVED` | `android-compose-api-defaults` | 不采用 Apple 44pt 作为实现值 | `DERIVED` |
| Apple 模式映射到 Android 官方组件 | `DERIVED` | Apple HIG + Android adaptive/components | 不在 Android 上仿制 SwiftUI chrome | `DERIVED` |

## SwiftUI-to-Android semantic mapping

| SwiftUI reference concept | Android production mapping | Status |
| --- | --- | --- |
| `NavigationSplitView` | `NavigationSuiteScaffold` + `ListDetailPaneScaffold`（如项目依赖版本支持） | `EXPERIMENTAL` |
| `List` / `Section` | `LazyColumn`、Material `ListItem`、section header | `DERIVED` |
| `ProgressView` | `LinearProgressIndicator` / `CircularProgressIndicator` | `DERIVED` |
| `Toggle` | Material 3 `Switch` | `DERIVED` |
| `confirmationDialog` / `Alert` | `AlertDialog` 或有意图的 `ModalBottomSheet` | `DERIVED` |
| `ToolbarItem` | Material 3 `TopAppBar` action | `DERIVED` |
| semantic system colors | Material 3 `ColorScheme` roles | `DERIVED` |
| Dynamic Type | Android font scale + responsive reflow | `DERIVED` |

## Excluded references

| Asset ID / category | Reason excluded |
| --- | --- |
| 其余 61 张 Apple 官方预览 | 与 settings/diagnostics/system utility 当前问题无直接关系，避免视觉污染 |
| Apple product bezels | 仅适合营销 mockup，`PROHIBITED-AS-EVIDENCE` 于 in-app UI |
| Apple technology previews | UClone 不使用 Apple Pay、Health、Wallet、Messages 等技术 |
| SF Symbols previews | Android 生产图标使用 Material Symbols/Icons |
| AI 生成概念板 | 只能探索，不能作为官方或规范证据 |

## Evidence boundary

本拆解证明“参考过哪些官方模式及其适用范围”，不证明 UClone 已符合 Apple HIG，也不证明 Android 界面已经实现。所有运行状态在生产 UI 落地和真机截图之前保持 `UNVERIFIED`。
