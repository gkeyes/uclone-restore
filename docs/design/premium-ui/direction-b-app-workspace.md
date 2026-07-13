# 方向 B：App 操作台

- 推荐等级：备选
- 审批状态：`EXPERIMENTAL`
- 功能变化：功能保留，但部分顶层入口会移动到 App/设置上下文
- 实施风险：中至高

## 1. Visual thesis

方向 B 把 UClone 视为“围绕一个 App 处理数据状态的操作台”。用户不先理解工作区、备份类型和全局页面，而是先选择 App，再在同一工作区查看状态、数据来源、历史和可执行动作。系统级诊断与设置退居管理层。

产品承诺：

> 选中一个 App，就能在一个连续工作区里看清它两侧的安装、当前数据、备份、任务和下一步。

## 2. Composition

### Compact phone

```text
┌──────────────────────────────┐
│ UClone             全局状态 ● │
├──────────────────────────────┤
│ 当前 App：小红书          [切换]│
│ user0  分数据正在使用           │
│ user10 已安装 · 已解锁          │
├──────────────────────────────┤
│ [状态] [数据] [任务] [工具]      │  App workspace
├──────────────────────────────┤
│ 主数据返回点                    │
│ 2026-07-13 · 1.4 GB      [还原] │
│ 分身回滚                        │
│ 2026-07-13 · 2.1 GB      [查看] │
└──────────────────────────────┘
│ 概览     App      任务     设置  │
└──────────────────────────────┘
```

- 顶层缩减为 4 个：概览、App、任务、设置。
- 数据页移动为 App 工作区内的“数据”页签。
- 诊断移动到设置中的“系统与诊断”。
- App 工作区内部使用二级 segmented control/tab 展示状态、数据、任务、工具。

以上移动全部是 `EXPERIMENTAL`，没有用户明确批准不得实施。

### Medium / expanded

```text
┌─────────────┬────────────────────┬──────────────────┐
│ App 列表      │ App 工作区           │ 任务/备份检查器     │
│ 小红书        │ 状态与主动作          │ 当前阶段/历史/日志   │
│ Chrome       │ 数据来源与回滚         │                  │
│ Blocker      │ 安装与规则            │                  │
└─────────────┴────────────────────┴──────────────────┘
```

- 第一 pane 固定 App 列表。
- 第二 pane 是 App 工作区。
- 第三 pane 根据选中对象显示任务或备份详情。

## 3. Navigation

| Window | 顶层导航 | 上下文导航 |
| --- | --- | --- |
| Compact | 4 项 Material navigation bar | App 工作区 segmented/tab |
| Medium | Navigation rail | App 列表 + detail |
| Expanded | Rail/permanent drawer | 三 pane workspace |

与方向 A 的实质差异：B 改变信息架构和入口层级，不只是换导航组件。

## 4. Information density

- 密度：中等。
- 每次只聚焦一个 App，减少全局备份列表的视觉负担。
- App 状态、两侧安装和主动作占第一屏。
- 数据与历史在二级上下文展示，解释更完整。
- 技术用户仍可在工具/诊断查看 UID、路径和原始状态。

## 5. Typography voice

- App 名称和数据状态成为页面主要标题。
- 系统总状态使用紧凑 banner，而非占据主视觉。
- 备份来源使用自然语言标题，rollback ID 为辅助等宽信息。
- 任务详情使用叙述式阶段文案，例如“正在保护分身当前数据”。

## 6. Color and material

- 每个 App 工作区使用轻量 accent 仅标识选中和主动作。
- 数据来源用标签/图形区分 MAIN、CLONE、ACTIVE、ROLLBACK，不为每类建立大色块。
- 多 pane 之间以 surface 层级和 divider 分隔，不使用浮动卡片。
- 导航 chrome 可使用轻量材质；任务和数据内容保持不透明。

## 7. Action hierarchy

- 当前数据状态对应的一个主动作固定在 App header。
- 数据页签的每个来源行只保留“查看/恢复”之一。
- 推送、主动快照、跨用户安装、审计收纳在“工具”，但仍可由 header 的情境入口直达。
- 删除与重置永远在详情或管理区，不出现在 header。

## 8. Motion

- App 选择切换第二 pane 内容，保持第一 pane 稳定。
- App 工作区页签使用轻量内容淡入，避免大范围 sliding。
- 任务开始后右侧/底部检查器固定，避免导航跳页。
- reduced motion 下取消 pane 内容位移。

## 9. Main App route proposal

| 新顶层 | 包含的现有功能 | 变化等级 |
| --- | --- | --- |
| 概览 | 原首页系统状态、当前任务、收藏 App | `EXPERIMENTAL` |
| App | 原 App 列表、App 详情、数据、备份详情的 App 上下文；涉及移动数据入口 | `EXPERIMENTAL` |
| 任务 | 原历史 + 当前任务详情 | `EXPERIMENTAL` |
| 设置 | 原设置 + 诊断；涉及移动诊断入口 | `EXPERIMENTAL` |

所有原功能均保留，但入口变化可能影响既有习惯、桌面 shortcut 深链和返回栈，必须单独批准和测试。

## 10. Launcher module translation

- 顶层变为“概览 / App / 更多”三项。
- “更多”内保留控制和日志两个 section/子页。
- App 页面是核心，选择结果与 Relay/Hook 的已记录诊断摘要在同一工作区显示；不推导实时 Hook 可用性。
- 这是功能移动，必须与主 App 方向一起审批，不能先行实施。

## 11. Official constraints retained

- Compact 使用 3-5 项 Android navigation bar。
- medium/expanded 使用 adaptive list-detail/pane 模式。
- 标准开关、列表、对话框、进度和 48dp 触控目标。
- 状态使用多重线索，支持大字体重排。

## 12. Product-specific choices

- App 是核心对象，状态/数据/任务围绕同一包组织。
- 主动作由 MAIN/CLONE/UNKNOWN 决定。
- 数据来源和目标方向在工作区持续可见。
- 技术诊断降为管理上下文，但不删除。

## 13. Risks and cost

- 高风险：移动“数据”和“诊断”会改变既有认知和入口。
- 高风险：现有 shortcut、手工返回栈和页面参数需要导航架构调整。
- 风险：App 上下文与全局工作区维护存在边界，需要防止维护工具被隐藏。
- 成本：高于方向 A，可能需要 Navigation Compose 或更系统的路由重构；不能与纯视觉改动混在一个提交。
- 回滚：必须按导航壳、App workspace、数据迁移、诊断迁移拆分提交。

## 14. Approval gate

方向 B 只有在用户明确批准“移动数据页与诊断入口”后才能实施。选择视觉风格不自动授权信息架构变化。
