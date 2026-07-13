# UClone Premium UI 方向对比

| Dimension | 方向 A：系统控制台 | 方向 B：App 操作台 |
| --- | --- | --- |
| Visual thesis | 状态优先、可审计、安静的系统控制台 | 围绕单个 App 连续完成状态、数据和任务操作 |
| Composition | 系统状态 -> 当前任务 -> 各领域列表 | App header -> 状态/数据/任务/工具工作区 |
| Compact navigation | 6 个现有顶层入口进入 modal drawer | 4 项底栏：概览、App、任务、设置 |
| Expanded navigation | rail/permanent drawer + list-detail | App list + workspace + inspector 三 pane |
| 功能层级 | 全部现有层级不变 | 数据并入 App；诊断并入设置 |
| Density | 中高，扫描效率优先 | 中等，单 App 叙事和上下文优先 |
| Typography voice | 短标题、状态值、技术次级信息 | App 名称/状态突出，任务阶段更叙述式 |
| Color/material | 克制 chrome 材质 + 不透明数据面 | App accent + pane surface，数据标签轻量 |
| Cards | 仅真正独立条目，section 不浮动 | pane 内分组和列表，不做卡片瀑布 |
| Action hierarchy | 每组一个主动作，工具行，危险区 | App header 一个主动作，其他动作按页签组织 |
| Motion | 局部状态更新、稳定列表 | pane/选中对象更新、检查器持续可见 |
| Main App impact | 主要是导航壳、主题和页面组件整理 | 信息架构、路由和深链都会变化 |
| Module impact | 保留 4 页，重新组织视觉和动作 | 变为 3 页并移动控制/日志 |
| Official constraints retained | Android 48dp、标准组件、adaptive、semantics | 同左，并符合紧凑窗口 3-5 项底栏 |
| Product truth retained | 完整保留 | 功能保留，但入口迁移需审批 |
| Experimental choices | drawer/rail 具体实现、轻量材质 | 4 顶层、App workspace、诊断迁移、三 pane |
| Implementation cost | 中 | 高 |
| Regression risk | 低至中 | 中至高 |
| 最适合 | 希望先稳定提升品质、不再扩大逻辑风险 | 愿意投入导航重构，追求更强 App 中心体验 |

## 结论

方向 A 是当前推荐方案，因为它能解决 6 项底栏拥挤、按钮堆叠、状态表达和页面密度问题，同时不移动功能、不改变数据语义，也不要求立即引入新导航依赖。

方向 B 的产品体验潜力更高，但它不是纯视觉重构。它会移动数据与诊断入口、改变深链与返回栈，必须作为独立信息架构项目批准和实施。

## 审批选项

- `A`：批准系统控制台方向，后续按不移动功能的原则形成实现切片。
- `B`：批准 App 操作台方向，并明确授权移动“数据”和“诊断”入口。
- `A+B refinement`：保留 A 的导航层级，只吸收 B 的 App 详情工作区构图，不移动顶层入口。

未获得选择前，两套方向均保持 `EXPERIMENTAL`，不得修改生产 Kotlin/UI。
