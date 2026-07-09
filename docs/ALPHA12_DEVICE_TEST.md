# Alpha 12 Device Test

Focus: verify that UI state follows switch/restore completion immediately.

## Expected behavior

1. Enable `任务完成后关闭分身系统`.
2. Run a favorite App `切换`.
3. Confirm task log contains:
   - `STOP_CLONE_AFTER_TASK=1`
   - `STATE_AFTER_STOP=User is not started: 10`
4. Return to Home without pressing `检测`.
5. Home should show:
   - `分身状态`: `User is not started: 10`
   - message: `任务完成，分身已关闭`
   - favorite action changed from `切换` to `还原`.

## Notes

- No artificial delay is added for the environment update.
- The ViewModel refreshes environment, restore backups, switch markers, and history in the same task-finalization update.
