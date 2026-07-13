# Premium UI Design Gate

本目录是 UClone Android UI 重构的审计、艺术方向和规格交付物。当前未修改生产 UI。

## Read order

1. [functional-inventory.md](functional-inventory.md)
2. [product-truth.md](product-truth.md)
3. [state-truth.md](state-truth.md)
4. [action-state-matrix.md](action-state-matrix.md)
5. [official-source-evidence.md](official-source-evidence.md)
6. [apple-official-reference-decomposition.md](apple-official-reference-decomposition.md)
7. [direction-matrix.md](direction-matrix.md)
8. [direction-a-system-console.md](direction-a-system-console.md)
9. [direction-b-app-workspace.md](direction-b-app-workspace.md)
10. [visual-contract.md](visual-contract.md)
11. [screenshot-matrix.md](screenshot-matrix.md)

## Current gate

- `PROJECT-VERIFIED`：功能、状态和数据源已从当前分支核对。
- `OFFICIAL-VERIFIED`：所列 Apple/Android 官方来源在本轮打开或由本地官方 manifest 核验。
- `EXPERIMENTAL`：方向 A、方向 B 与所有导航变化。
- `UNVERIFIED`：运行截图、深色、大字体、TalkBack、adaptive 和真实交互。

用户选择 `A`、`B` 或 `A+B refinement` 之前，不进入生产 Kotlin/UI 实现。
