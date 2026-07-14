# Premium UI Design Gate

本目录是 UClone Android UI 重构的审计、艺术方向、实现合同和真机验收记录。生产 UI 已进入 `design/premium-system-utility-ui` 分支实现阶段。

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
- `IMPLEMENTED LOCALLY`：本地差异保留 Backdrop 1.0.2 光学底栏与五项顶级导航，并统一连续分组页面、Apple 参考 token、静态珍珠面工具和高对比短主动作。
- `AUTOMATED-VERIFICATION PENDING`：现有测试覆盖导航归属、完整 26 项动作集合、48dp 触控和禁用/危险语义；当前本地差异尚未通过对应 GitHub 模拟器运行，不复用旧构建结论。
- `UNVERIFIED`：当前提交对应的真机截图、深色、大字体、TalkBack、性能和真实交互。

真机 `UNVERIFIED` 项只能由当前提交对应的 GitHub 固定签名 APK 关闭；旧 APK 和旧截图不得复用为证据。
