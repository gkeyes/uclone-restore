# v0.1.0-alpha.6 真机测试说明

目标：验证后台无感启动 user10、PIN 验证后 CE 可读、分身数据复制，以及任务结束后按需关闭 user10。

## 1. 安装与设置

1. 安装 `v0.1.0-alpha.6` APK 到主系统 user0。
2. 打开 App，授予 KernelSU/Magisk root。
3. 进入「设置」，开启「分身自动解锁」，填写分身锁屏 PIN/密码，保存设置。
4. 默认数据范围中确认 `external` 已开启。小红书这类 App 的 `Android/data` 数据量较大，建议保留开启。
5. 「自动关闭本次启动的分身系统」默认开启。

PIN 使用 Android Keystore 密钥加密保存在 user0，并通过 stdin 传入 root shell；任务日志和 `su -c` 参数都不记录明文。

## 2. 诊断无感启动

进入「诊断」页，点击「无感启动分身」。

预期日志：

```text
ENSURE_CLONE_CE_BEGIN
START_USER_BEGIN
VERIFY_BEGIN
VERIFY_RESULT=SUCCESS
WAIT_AFTER_VERIFY_...
USER10_CE_READY=1
STOP_CLONE_AFTER_TASK=0 reason=persistent_lifecycle_action startedByTask=1
```

「无感启动分身」是持续生命周期操作：即使本次操作启动了 user10，也应保持 `RUNNING_UNLOCKED`，不会自动关闭。只有需要临时读取分身数据的备份、切换或推送任务，才按「自动关闭本次启动的分身系统」设置在任务结束后尝试关闭。

如果 user10 本来就是 `RUNNING_UNLOCKED`，日志应显示：

```text
ENSURE_CLONE_UNLOCK_RESULT=READY_ALREADY
STOP_CLONE_AFTER_TASK=0 startedByTask=0
```

这版不应出现：

```text
am switch-user
input text
PIN_PAD_TAPS
KEYCODE_
```

## 3. 备份与切换

选择一个已在 user10 登录的 App：

1. 开启「分身自动解锁」后，在分身未启动时直接点击「建立主动备份」。
2. 预期 App 自动后台启动 user10、验证 PIN、等待 `RUNNING_UNLOCKED`，然后复制 CE/DE/external 数据。
3. 任务完成后，如果 user10 是本次任务启动的，应发出非阻塞 `stop-user 10`，并通过状态轮询确认停止；不得使用 `-w` 阻塞任务。
4. 数据页应能看到主动备份大小。
5. 点击首页收藏 App 的「切换」，成功后按钮应变为「还原」。
6. 点击「还原」后应恢复切换前的主系统数据，并清除还原标记。

## 4. 失败时需要回传

优先回传最新任务日志：

```sh
su -c 'ls -lt /data/adb/uclone/logs | head'
su -c 'cat /data/adb/uclone/logs/<最新日志>.log'
```

重点看：

- `STATE_BEFORE_UNLOCK`
- `START_USER_OUTPUT`
- `VERIFY_RESULT`
- `STATE_AFTER_VERIFY`
- `ERR_CLONE_*`
- `STOP_USER_OUTPUT`
- `COPIED:/data/user/10/<包名>`
- `COPIED:/data/media/10/Android/data/<包名>`
