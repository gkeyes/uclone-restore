# v0.1.0-alpha.5 真机测试说明

目标：验证 user10 CE 解锁 gate、主动备份/切换不再误报成功，并导出恢复一致性审计包。

## 1. 安装与授权

1. 安装 `v0.1.0-alpha.5` APK 到主系统 user0。
2. 打开 App，授予 KernelSU/Magisk root。
3. 确认首页显示：
   - Root 正常
   - 当前用户为 `0`
   - `CE gate` 只有在分身已解锁时才应显示 `RUNNING_UNLOCKED`

## 2. user10 CE gate

进入「诊断」页：

1. 点击「重新检测」。
2. 点击「检测 CE 状态」。

预期：

- 如果分身未解锁，任务应失败，日志包含 `ERR_USER10_CE_LOCKED` 或 `ERR_USER10_NOT_STARTED`。
- 如果分身已解锁，任务应成功，日志包含 `USER10_CE_READY=1`。
- 「检测 CE 状态」不会执行 `am start-user`、`am unlock-user` 或 `am switch-user`，不会删除文件。
- 如需实验性自动解锁，先到「设置」填写分身锁屏 PIN/密码并点击「保存设置」，再在「诊断」点击「带密码尝试解锁」。
- 如果未保存密码，按钮仍可点击，但只会提示先填写密码，不会执行 root 命令。
- 带密码尝试会先执行 `cmd lock_settings verify --old <已脱敏> --user 10`；如果仍未解锁，会切换到 user10，唤醒/滑开锁屏，数字密码优先用 KEYCODE_0-9 输入，再兜底使用 `input text`。日志只记录结果分类、焦点摘要和密码长度，不记录明文。

## 3. 主动备份和切换

选择低风险 App，例如小红书：

1. 在分身已解锁后点击「建立主动备份」。
2. 如果 `CE gate` 不是 `RUNNING_UNLOCKED`，默认包含 CE 时应失败，不应生成只有 DE 的假成功快照。
3. 点击「切换到分身态」。
4. 成功后首页/详情页按钮应变为「还原主系统态」。
5. 点击「还原主系统态」后，按钮应回到「切换到分身态」。

## 4. 审计包

App 详情页点击「生成恢复审计包」。

输出目录：

```text
/data/adb/uclone/audit/<包名>/<时间>/
```

关键文件：

- `summary.md`
- `file_tree_ce.txt`
- `file_tree_de.txt`
- `ls_lZ_ce.txt`
- `package_dump.txt`
- `cmd_package_dump.txt`
- `appops_pkg.txt`
- `appops_uid.txt`
- `uid.txt`
- `user_state.txt`

审计包是只读采集，不会恢复、不会删除、不会执行 `restorecon`。

## 5. 需要回传的日志

如果备份/切换失败，优先回传 App 显示的最新任务日志，或从设备取：

```sh
adb shell su -c 'ls -lt /data/adb/uclone/logs | head'
adb shell su -c 'cat /data/adb/uclone/logs/<最新日志>.log'
```

如果恢复效果异常，再回传对应审计包：

```sh
adb shell su -c 'ls -lt /data/adb/uclone/audit/<包名>'
adb shell su -c 'cat /data/adb/uclone/audit/<包名>/<时间>/summary.md'
```
