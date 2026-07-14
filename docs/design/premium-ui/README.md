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
- `IMPLEMENTED`：Backdrop 1.0.2 光学底栏、五项顶级导航、连续分组页面和操作层级；内容树中的工具和主动作使用同尺寸静态半透明材质，避免循环 Backdrop。
- `AUTOMATED-VERIFIED`：导航归属、完整 26 项动作集合、48dp 触控、禁用/危险语义和光学底栏渲染已进入 JVM/Android 自动测试，Android 测试由 GitHub 模拟器执行。
- `UNVERIFIED`：当前提交对应的真机截图、深色、大字体、TalkBack、性能和真实交互。

真机 `UNVERIFIED` 项只能由当前提交对应的 GitHub 固定签名 APK 关闭；旧 APK 和旧截图不得复用为证据。
