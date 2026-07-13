package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
import com.uclone.restore.model.RestoreBackupEntry
import com.uclone.restore.model.PassiveBackupStateKind
import com.uclone.restore.model.StepStatus
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskStage
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskStep
import com.uclone.restore.model.TaskType
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.model.CrossUserInstallMode
import com.uclone.restore.root.RootEnvironmentChecker
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.ShellOutput
import com.uclone.restore.root.ShellResult
import com.uclone.restore.root.ShellStream
import com.uclone.restore.root.shellQuote
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class SyncEngine(
    private val shell: RootShellExecutor,
    private val environmentChecker: RootEnvironmentChecker,
    private val logStore: TaskRepository,
    private val appPackage: String,
    private val signingIdentityProvider: PackageSigningIdentityProvider,
) {
    suspend fun checkEnvironment(settings: UCloneSettings) = environmentChecker.check(settings)

    suspend fun validateSettingsTarget(settings: UCloneSettings) =
        environmentChecker.validateSettingsTarget(settings)

    suspend fun loadWorkspaceIndex(settings: UCloneSettings): WorkspaceIndex {
        val result = shell.exec(
            workspaceIndexScript(
                rootDir = settings.rootDir,
                mainUserId = settings.mainUserId,
            ),
            60,
        )
        check(result.isSuccess) {
            result.stderr.lineSequence().firstOrNull(String::isNotBlank)
                ?: "无法读取 UClone 工作区索引（exit=${result.exitCode}）"
        }
        return WorkspaceIndexParser.parse(result.stdout).verifySwitchMarkerSignatures { packageName ->
            runCatching { signingIdentityProvider.certificateSha256(packageName) }.getOrNull()
        }
    }

    suspend fun scanWorkspaceOwnership(
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.SCAN_WORKSPACE_OWNERSHIP,
        packageName = "workspace",
        settings = settings,
        labels = listOf("检查工作区", "扫描文件归属", "汇总容量"),
        script = WorkspaceOwnershipScripts.scanTask(settings.rootDir),
        report = report,
        requestId = requestId,
    )

    suspend fun repairWorkspaceOwnership(
        settings: UCloneSettings,
        expectedCanonicalRoot: String,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.REPAIR_WORKSPACE_OWNERSHIP,
        packageName = "workspace",
        settings = settings,
        labels = listOf("检查工作区", "扫描文件归属", "分批修复归属", "验证结果", "写入完成标记"),
        script = WorkspaceOwnershipScripts.repair(settings.rootDir, expectedCanonicalRoot),
        report = report,
        requestId = requestId,
    )

    suspend fun installAcrossUsers(
        packageName: String,
        targetUserId: Int,
        mode: CrossUserInstallMode,
        rule: AppRule,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = when (mode) {
            CrossUserInstallMode.INSTALL_ONLY -> TaskType.INSTALL_TO_OTHER_USER
            CrossUserInstallMode.INSTALL_WITH_PERMISSIONS -> TaskType.INSTALL_WITH_PERMISSIONS_TO_OTHER_USER
            CrossUserInstallMode.INSTALL_AND_SYNC -> TaskType.INSTALL_AND_SYNC_TO_OTHER_USER
        },
        packageName = packageName,
        settings = settings,
        labels = when (mode) {
            CrossUserInstallMode.INSTALL_ONLY -> listOf("检查安装状态", "启用目标用户安装", "验证目标 UID")
            CrossUserInstallMode.INSTALL_WITH_PERMISSIONS ->
                listOf("检查安装状态", "启用目标用户安装", "迁移权限/AppOps", "验证结果")
            CrossUserInstallMode.INSTALL_AND_SYNC ->
                listOf("检查安装状态", "启用目标用户安装", "准备源数据", "同步目标数据", "验证结果")
        },
        script = CrossUserInstallScripts.build(
            packageName = packageName,
            targetUserId = targetUserId,
            mode = mode,
            rule = rule,
            settings = settings,
            appPackage = appPackage,
            requestId = requestId,
        ),
        report = report,
        requestId = requestId,
    )

    suspend fun captureSnapshot(
        packageName: String,
        rule: AppRule,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = captureSnapshotTask(
        type = TaskType.CAPTURE_SNAPSHOT_FROM_CLONE,
        packageName = packageName,
        rule = rule,
        settings = settings,
        report = report,
        requestId = requestId,
    )

    suspend fun restoreSnapshot(
        packageName: String,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
        compatibility: RestoreCompatibilityOptions = RestoreCompatibilityOptions(),
    ): TaskRecord = restoreSnapshotTask(
        type = TaskType.RESTORE_SNAPSHOT_TO_MAIN,
        packageName = packageName,
        settings = settings,
        report = report,
        requestId = requestId,
        compatibility = compatibility,
    )

    suspend fun restoreFromCloneLatest(
        packageName: String,
        rule: AppRule,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.RESTORE_FROM_CLONE_LATEST,
        packageName = packageName,
        settings = settings,
        labels = listOf(
            "检查 root",
            "读取分身当前数据",
            "更新主动备份",
            "生成被动备份",
            "恢复到主系统",
            "验证结果",
        ),
        script = ShellScripts.restoreFromCloneLatest(packageName, rule, settings, appPackage),
        report = report,
        requestId = requestId,
    )

    private suspend fun captureSnapshotTask(
        type: TaskType,
        packageName: String,
        rule: AppRule,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String,
    ): TaskRecord = runScriptTask(
        type = type,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "检查分身解锁", "停止分身 App", "复制数据", "采集权限/AppOps", "激活快照"),
        script = ShellScripts.capture(packageName, rule, settings, appPackage),
        report = report,
        requestId = requestId,
    )

    private suspend fun restoreSnapshotTask(
        type: TaskType,
        packageName: String,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String,
        compatibility: RestoreCompatibilityOptions,
    ): TaskRecord = runScriptTask(
        type = type,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "读取主动备份", "生成被动备份", "覆盖目录内容", "恢复权限/AppOps", "完成"),
        script = ShellScripts.restore(packageName, settings, appPackage, compatibility),
        report = report,
        requestId = requestId,
    )

    suspend fun switchToCloneState(
        packageName: String,
        rule: AppRule,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord {
        return runScriptTask(
            type = TaskType.SWITCH_TO_CLONE_STATE,
            packageName = packageName,
            settings = settings,
            labels = listOf("检查 root", "读取分身当前数据", "生成被动备份", "恢复分身态", "记录还原按钮", "完成"),
            script = ShellScripts.switchFromCloneLatest(packageName, rule, settings, appPackage),
            report = report,
            requestId = requestId,
        )
    }

    suspend fun pushMainToClone(
        packageName: String,
        rule: AppRule,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord {
        return runScriptTask(
            type = TaskType.PUSH_MAIN_TO_CLONE,
            packageName = packageName,
            settings = settings,
            labels = listOf("检查 root", "准备分身目标", "读取主系统数据", "备份分身数据", "推送到分身", "完成"),
            script = ShellScripts.pushMainToClone(packageName, rule, settings, appPackage),
            report = report,
            requestId = requestId,
        )
    }

    suspend fun restoreCloneRollback(
        packageName: String,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
        compatibility: RestoreCompatibilityOptions = RestoreCompatibilityOptions(),
    ): TaskRecord = runScriptTask(
        type = TaskType.RESTORE_CLONE_ROLLBACK_TO_CLONE,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "读取分身回滚", "备份分身当前数据", "恢复到分身", "恢复权限/AppOps", "完成"),
        script = ShellScripts.restoreCloneRollback(packageName, settings, appPackage, compatibility),
        report = report,
        requestId = requestId,
    )

    suspend fun restoreSwitchMainState(
        packageName: String,
        rollbackId: String,
        rule: AppRule,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
        compatibility: RestoreCompatibilityOptions = RestoreCompatibilityOptions(),
    ): TaskRecord = runScriptTask(
        type = TaskType.RESTORE_SWITCH_MAIN_STATE,
        packageName = packageName,
        settings = settings,
        labels = if (settings.forceUpdateCloneDataBeforeMainRestore) {
            listOf("检查 root", "更新分系统数据", "读取切换前被动备份", "恢复主系统态", "清除切换标记", "完成")
        } else {
            listOf("检查 root", "读取切换前被动备份", "生成被动备份", "恢复主系统态", "清除切换标记", "完成")
        },
        script = ShellScripts.restoreSwitchMainState(
            packageName = packageName,
            rollbackId = rollbackId,
            rule = rule,
            settings = settings,
            appPackage = appPackage,
            compatibility = compatibility,
        ),
        report = report,
        requestId = requestId,
    )

    suspend fun rollback(
        packageName: String,
        rollbackId: String,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
        compatibility: RestoreCompatibilityOptions = RestoreCompatibilityOptions(),
    ): TaskRecord = runScriptTask(
        type = TaskType.ROLLBACK_MAIN_DATA,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "读取被动备份", "停止相关进程", "恢复旧数据", "恢复权限/AppOps", "结束切换会话", "完成"),
        script = ShellScripts.rollback(
            packageName,
            rollbackId,
            settings,
            appPackage,
            compatibility = compatibility,
        ),
        report = report,
        requestId = requestId,
    )

    suspend fun resetSwitchState(
        packageName: String,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.RESET_SWITCH_STATE,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "将数据状态设为未知", "刷新状态"),
        script = ShellScripts.resetSwitchState(packageName, settings, appPackage),
        report = report,
        requestId = requestId,
    )

    suspend fun deleteSnapshot(
        packageName: String,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.DELETE_SNAPSHOT,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "读取 active 快照", "统计大小", "删除 active", "确认清理"),
        script = ShellScripts.deleteSnapshot(packageName, settings, appPackage),
        report = report,
        requestId = requestId,
    )

    suspend fun deleteRestoreBackup(
        packageName: String,
        rollbackId: String,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.DELETE_RESTORE_BACKUP,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "读取被动备份", "验证非当前返回点", "删除备份目录", "完成"),
        script = ShellScripts.deleteRestoreBackup(packageName, rollbackId, settings, appPackage),
        report = report,
        requestId = requestId,
    )

    suspend fun deleteCloneRollback(
        packageName: String,
        rollbackId: String,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.DELETE_CLONE_ROLLBACK,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "读取分身回滚", "删除指定备份", "完成"),
        script = ShellScripts.deleteCloneRollback(packageName, rollbackId, settings, appPackage),
        report = report,
        requestId = requestId,
    )

    suspend fun recoverInterruptedTransaction(
        transactionRequestId: String,
        packageName: String,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.RECOVER_INTERRUPTED_TRANSACTION,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查事务记录", "确认 App 门禁", "验证回滚数据", "自动回滚", "恢复 App 状态"),
        script = TransactionRecoveryShell.build(transactionRequestId, settings, appPackage),
        report = report,
        requestId = requestId,
        relatedTransactionId = transactionRequestId,
    )

    suspend fun requestTransactionCancel(requestId: String, settings: UCloneSettings): Boolean {
        require(requestId.matches(Regex("[A-Za-z0-9_.-]{1,128}")))
        val transactionDir = "${settings.rootDir}/transactions/$requestId"
        val result = shell.exec(
            """
                ${WorkspacePathGuard.require(settings.rootDir)}
                TRANSACTION_DIR=${shellQuote(transactionDir)}
                TRANSACTION_JSON="${'$'}TRANSACTION_DIR/transaction.json"
                CANCEL_PATH="${'$'}TRANSACTION_DIR/cancel.requested"
                [ -f "${'$'}TRANSACTION_JSON" ] || exit 2
                CANCEL_TMP="${'$'}CANCEL_PATH.tmp.${'$'}${'$'}"
                printf '%s\n' "${'$'}(/system/bin/date +%s%3N 2>/dev/null || /system/bin/date +%s)" > "${'$'}CANCEL_TMP" || exit 3
                chmod 600 "${'$'}CANCEL_TMP" || exit 3
                mv -f "${'$'}CANCEL_TMP" "${'$'}CANCEL_PATH" || exit 3
                sync
                echo "TRANSACTION_CANCEL_REQUESTED=$requestId"
            """.trimIndent(),
            timeoutSeconds = 5,
        )
        return result.isSuccess
    }

    suspend fun probeCloneCe(
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.PROBE_CLONE_CE,
        packageName = "user${settings.cloneUserId}",
        settings = settings,
        labels = listOf("检查 root", "读取状态", "探测 CE/DE", "输出结论"),
        script = ShellScripts.probeCloneCe(settings),
        report = report,
        requestId = requestId,
    )

    suspend fun unlockCloneWithCredential(
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.UNLOCK_CLONE_WITH_CREDENTIAL,
        packageName = "user${settings.cloneUserId}",
        settings = settings,
        labels = listOf("检查 root", "后台启动分身", "验证 PIN", "等待 CE 解锁", "保持分身运行", "完成"),
        script = ShellScripts.unlockCloneWithCredential(settings),
        report = report,
        requestId = requestId,
    )

    suspend fun debugCloneSystem(
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.DEBUG_CLONE_SYSTEM,
        packageName = "user${settings.cloneUserId}",
        settings = settings,
        labels = listOf("检查 root", "采集用户状态", "采集包/UID", "采集路径", "采集权限入口", "输出 0.2 结论"),
        script = ShellScripts.debugCloneSystem(settings, appPackage),
        report = report,
        requestId = requestId,
    )

    suspend fun auditRestoreConsistency(
        packageName: String,
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.AUDIT_RESTORE_CONSISTENCY,
        packageName = packageName,
        settings = settings,
        labels = listOf("检查 root", "创建审计目录", "采集文件树", "采集 UID/SELinux", "采集权限/AppOps", "写入 summary"),
        script = ShellScripts.auditRestoreConsistency(packageName, settings, appPackage),
        report = report,
        requestId = requestId,
    )

    suspend fun latestSnapshotTime(packageName: String, settings: UCloneSettings): Long? {
        return latestSnapshotMetadata(packageName, settings)?.updatedAt
    }

    suspend fun latestSnapshotMetadata(packageName: String, settings: UCloneSettings): SnapshotMetadata? {
        return snapshotMetadata(settings).getValueOrNull(packageName)
    }

    suspend fun snapshotMetadata(settings: UCloneSettings): Map<String, SnapshotMetadata> {
        val root = "${settings.rootDir}/snapshots"
        val script = """
            ${WorkspacePathGuard.inspect(settings.rootDir)}
            [ -d ${shellQuote(root)} ] || exit 0
            for f in ${shellQuote(root)}/*/active/manifest.json; do
              [ -f "${'$'}f" ] || continue
              pkg=${'$'}(basename "${'$'}(dirname "${'$'}(dirname "${'$'}f")")")
              ts=${'$'}(stat -c %Y "${'$'}f" 2>/dev/null || echo 0)
              size=${'$'}(sed -n 's/.*"snapshotSizeKb":"\([0-9][0-9]*\)".*/\1/p' "${'$'}f" | head -1)
              [ -n "${'$'}size" ] || size=${'$'}(du -skx "${'$'}(dirname "${'$'}f")" 2>/dev/null | awk '{print ${'$'}1}')
              echo "${'$'}pkg ${'$'}ts ${'$'}size"
            done
        """.trimIndent()
        val result = shell.exec(script, 30)
        return result.stdout.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(" ")
            val packageName = parts.firstOrNull() ?: return@mapNotNull null
            val millis = parts.getOrNull(1)?.toLongOrNull()?.times(1000) ?: return@mapNotNull null
            packageName to SnapshotMetadata(
                updatedAt = millis,
                sizeKb = parts.getOrNull(2)?.toLongOrNull(),
            )
        }.toMap()
    }

    suspend fun snapshotTimes(settings: UCloneSettings): Map<String, Long> =
        snapshotMetadata(settings).mapValues { it.value.updatedAt }

    suspend fun switchMarkerIds(settings: UCloneSettings): Map<String, String> =
        loadWorkspaceIndex(settings).switchMarkers

    suspend fun switchMarkerId(packageName: String, settings: UCloneSettings): String? =
        switchMarkerIds(settings)[packageName]

    suspend fun appDataState(packageName: String, settings: UCloneSettings): AppDataState {
        require(ANDROID_PACKAGE_NAME.matches(packageName)) { "Unsafe package name: $packageName" }
        val expectedSigningCertificateSha256 = runCatching {
            signingIdentityProvider.certificateSha256(packageName)
        }.getOrElse {
            return AppDataState.Unknown
        }
        val result = shell.exec(
            appDataStateScript(
                packageName = packageName,
                rootDir = settings.rootDir,
                mainUserId = settings.mainUserId,
                expectedSigningCertificateSha256 = expectedSigningCertificateSha256,
            ),
            10,
        )
        if (!result.isSuccess) return AppDataState.Unknown
        return parseAppDataState(result.stdout)
    }

    private fun appDataStateScript(
        packageName: String,
        rootDir: String,
        mainUserId: Int,
        expectedSigningCertificateSha256: String,
    ): String = """
        ${WorkspacePathGuard.inspect(rootDir)}
        PKG=${shellQuote(packageName)}
        MAIN_USER=$mainUserId
        EXPECTED_SIGNING_CERTIFICATE=${shellQuote(expectedSigningCertificateSha256)}
        UNKNOWN_SWITCH_MARKER=${shellQuote(UNKNOWN_SWITCH_MARKER)}
        MARKER="${'$'}ROOT/switches/${'$'}PKG/active"

        if [ ! -e "${'$'}MARKER" ] && [ ! -L "${'$'}MARKER" ]; then
          printf 'APP_DATA_STATE\tMAIN\n'
          exit 0
        fi
        if [ ! -f "${'$'}MARKER" ] || [ -L "${'$'}MARKER" ]; then
          printf 'APP_DATA_STATE\tUNKNOWN\n'
          exit 0
        fi
        MARKER_REAL=${'$'}(readlink -f "${'$'}MARKER" 2>/dev/null || true)
        if [ "${'$'}MARKER_REAL" != "${'$'}ROOT/switches/${'$'}PKG/active" ]; then
          printf 'APP_DATA_STATE\tUNKNOWN\n'
          exit 0
        fi

        MARKER_ID=${'$'}(sed -n '1p' "${'$'}MARKER" 2>/dev/null | tr -d '\r')
        case "${'$'}MARKER_ID" in
          "${'$'}UNKNOWN_SWITCH_MARKER")
            printf 'APP_DATA_STATE\tUNKNOWN\n'
            ;;
          ''|.|..|*[!A-Za-z0-9_.-]*)
            printf 'APP_DATA_STATE\tUNKNOWN\n'
            ;;
          *)
            MANIFEST="${'$'}ROOT/rollback/${'$'}PKG/${'$'}MARKER_ID/manifest.json"
            if [ -f "${'$'}MANIFEST" ] && [ ! -L "${'$'}MANIFEST" ]; then
              MANIFEST_REAL=${'$'}(readlink -f "${'$'}MANIFEST" 2>/dev/null || true)
              if [ "${'$'}MANIFEST_REAL" = "${'$'}ROOT/rollback/${'$'}PKG/${'$'}MARKER_ID/manifest.json" ]; then
                MANIFEST_SCHEMA=${'$'}(sed -n 's/.*"schemaVersion":\([0-9][0-9]*\).*/\1/p' "${'$'}MANIFEST" | head -1)
                MANIFEST_PACKAGE=${'$'}(sed -n 's/.*"packageName":"\([^"]*\)".*/\1/p' "${'$'}MANIFEST" | head -1)
                MANIFEST_STATE=${'$'}(sed -n 's/.*"stateKind":"\([^"]*\)".*/\1/p' "${'$'}MANIFEST" | head -1)
                MANIFEST_KIND=${'$'}(sed -n 's/.*"backupKind":"\([^"]*\)".*/\1/p' "${'$'}MANIFEST" | head -1)
                MANIFEST_SIGNING_CERTIFICATE=${'$'}(sed -n 's/.*"sourceSigningCertificateSha256":"\([0-9a-f,]*\)".*/\1/p' "${'$'}MANIFEST" | head -1)
                MANIFEST_SOURCE_USER=${'$'}(${manifestUserIdReadCommand("sourceUser", "\"${'$'}MANIFEST\"")})
                MANIFEST_TARGET_USER=${'$'}(${manifestUserIdReadCommand("targetUser", "\"${'$'}MANIFEST\"")})
                if [ "${'$'}MANIFEST_SCHEMA" = "$CURRENT_MANIFEST_SCHEMA" ] &&
                  [ "${'$'}MANIFEST_PACKAGE" = "${'$'}PKG" ] &&
                  [ "${'$'}MANIFEST_STATE" = "main" ] &&
                  [ "${'$'}MANIFEST_KIND" = "rollback" ] &&
                  [ "${'$'}MANIFEST_SIGNING_CERTIFICATE" = "${'$'}EXPECTED_SIGNING_CERTIFICATE" ] &&
                  [ "${'$'}MANIFEST_SOURCE_USER" = "${'$'}MAIN_USER" ] &&
                  [ "${'$'}MANIFEST_TARGET_USER" = "${'$'}MAIN_USER" ]; then
                  printf 'APP_DATA_STATE\tCLONE\t%s\n' "${'$'}MARKER_ID"
                else
                  printf 'APP_DATA_STATE\tUNKNOWN\n'
                fi
              else
                printf 'APP_DATA_STATE\tUNKNOWN\n'
              fi
            else
              printf 'APP_DATA_STATE\tUNKNOWN\n'
            fi
            ;;
        esac
    """.trimIndent()

    private fun parseAppDataState(output: String): AppDataState {
        val fields = output.lineSequence()
            .map { it.split('\t', limit = 3) }
            .firstOrNull { it.firstOrNull() == "APP_DATA_STATE" }
            ?: return AppDataState.Unknown
        return when (fields.getOrNull(1)) {
            "MAIN" -> AppDataState.Main
            "CLONE" -> fields.getOrNull(2)
                ?.takeIf { it != UNKNOWN_SWITCH_MARKER && SAFE_ROLLBACK_ID.matches(it) && it != "." && it != ".." }
                ?.let(AppDataState::Clone)
                ?: AppDataState.Unknown
            else -> AppDataState.Unknown
        }
    }

    suspend fun listRollbackIds(packageName: String, settings: UCloneSettings): List<String> {
        val path = "${settings.rootDir}/rollback/$packageName"
        val result = shell.exec(
            """
                ${WorkspacePathGuard.inspect(settings.rootDir)}
                [ -d ${shellQuote(path)} ] || exit 0
                for d in ${shellQuote(path)}/*; do
                  [ -f "${'$'}d/manifest.json" ] || continue
                  basename "${'$'}d"
                done
            """.trimIndent(),
            20,
        )
        return result.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    }

    suspend fun listRestoreBackups(settings: UCloneSettings): List<RestoreBackupEntry> {
        val rollbackRoot = "${settings.rootDir}/rollback"
        val switchRoot = "${settings.rootDir}/switches"
        val script = "${WorkspacePathGuard.inspect(settings.rootDir)}\n${restoreBackupListScript(rollbackRoot, switchRoot)}"
        val result = shell.exec(script, 30)
        return result.stdout.lineSequence().mapNotNull { line ->
            val parts = line.split("\t", limit = 7)
            val packageName = parts.getOrNull(0)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val rollbackId = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val createdAt = parts.getOrNull(2)?.toLongOrNull()?.times(1000) ?: 0L
            RestoreBackupEntry(
                packageName = packageName,
                rollbackId = rollbackId,
                createdAt = createdAt,
                sizeKb = parts.getOrNull(3)?.toLongOrNull(),
                isActiveSwitchBackup = parts.getOrNull(4) == "1",
                reason = parts.getOrNull(5)?.takeIf(String::isNotBlank) ?: "被动备份",
                stateKind = when (parts.getOrNull(6)) {
                    "main" -> PassiveBackupStateKind.MAIN
                    "clone" -> PassiveBackupStateKind.CLONE
                    else -> if (parts.getOrNull(4) == "1") PassiveBackupStateKind.MAIN else null
                },
            )
        }.sortedByDescending { it.createdAt }
            .distinctBy { it.packageName to it.stateKind }
            .toList()
    }

    suspend fun listCloneRollbackBackups(settings: UCloneSettings): List<RestoreBackupEntry> {
        val root = "${settings.rootDir}/clone_rollback"
        val script = """
            ${WorkspacePathGuard.inspect(settings.rootDir)}
            CLONE_ROLLBACK_ROOT=${shellQuote(root)}
            [ -d "${'$'}CLONE_ROLLBACK_ROOT" ] || exit 0
            for d in "${'$'}CLONE_ROLLBACK_ROOT"/*/latest; do
              [ -d "${'$'}d" ] || continue
              [ -f "${'$'}d/manifest.json" ] || continue
              pkg=${'$'}(basename "${'$'}(dirname "${'$'}d")")
              ts=${'$'}(stat -c %Y "${'$'}d" 2>/dev/null || echo 0)
              size=${'$'}(du -skx "${'$'}d" 2>/dev/null | awk '{print ${'$'}1}')
              reason=""
              if [ -f "${'$'}d/manifest.json" ]; then
                reason=${'$'}(sed -n 's/.*"reason":"\([^"]*\)".*/\1/p' "${'$'}d/manifest.json" | head -1)
              fi
              [ -n "${'$'}reason" ] || reason="推送到分身前生成"
              printf '%s\t%s\t%s\t%s\t%s\n' "${'$'}pkg" "latest" "${'$'}ts" "${'$'}size" "${'$'}reason"
            done
        """.trimIndent()
        val result = shell.exec(script, 30)
        return result.stdout.lineSequence().mapNotNull { line ->
            val parts = line.split("\t", limit = 5)
            val packageName = parts.getOrNull(0)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            RestoreBackupEntry(
                packageName = packageName,
                rollbackId = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: "latest",
                createdAt = parts.getOrNull(2)?.toLongOrNull()?.times(1000) ?: 0L,
                sizeKb = parts.getOrNull(3)?.toLongOrNull(),
                reason = parts.getOrNull(4)?.takeIf(String::isNotBlank) ?: "推送到分身前生成",
                isActiveSwitchBackup = false,
                isCloneRollback = true,
                stateKind = PassiveBackupStateKind.CLONE,
            )
        }.sortedByDescending { it.createdAt }
            .toList()
    }

    fun history(): List<TaskRecord> = logStore.all()

    suspend fun clearLogs(
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.CLEAR_LOGS,
        packageName = "logs",
        settings = settings,
        labels = listOf("检查 Root", "清理任务日志"),
        script = """
            echo "UCLONE_STAGE_BEGIN:CLEANUP"
            LOG_DIR=${shellQuote("${settings.rootDir}/logs")}
            mkdir -p "${'$'}LOG_DIR" || exit 10
            COUNT=0
            for f in "${'$'}LOG_DIR"/*.log; do
              [ -f "${'$'}f" ] || continue
              rm -f "${'$'}f" || exit 11
              COUNT=${'$'}((COUNT + 1))
            done
            echo "CLEARED_LOGS=${'$'}COUNT"
        """.trimIndent(),
        report = report,
        requestId = requestId,
    )

    suspend fun resetWorkspace(
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.RESET_WORKSPACE,
        packageName = "workspace",
        settings = settings,
        labels = listOf("检查 Root", "重置工作区"),
        script = "echo \"UCLONE_STAGE_BEGIN:CLEANUP\"\n${ShellScripts.resetWorkspace(settings)}",
        report = report,
        requestId = requestId,
    )

    suspend fun startCloneUser(
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.START_CLONE_USER,
        packageName = "user${settings.cloneUserId}",
        settings = settings,
        labels = listOf("检查 Root", "启动分身用户"),
        script = "echo \"UCLONE_STAGE_BEGIN:PRECHECK\"\n${ShellScripts.startCloneUser(settings)}",
        report = report,
        requestId = requestId,
    )

    suspend fun switchToCloneUser(
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.SWITCH_TO_CLONE_USER,
        packageName = "user${settings.cloneUserId}",
        settings = settings,
        labels = listOf("检查 Root", "进入分身用户"),
        script = "echo \"UCLONE_STAGE_BEGIN:COMMIT\"\nam switch-user ${settings.cloneUserId}",
        report = report,
        requestId = requestId,
    )

    suspend fun stopCloneUserByExplicitUserRequest(
        settings: UCloneSettings,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
    ): TaskRecord = runScriptTask(
        type = TaskType.STOP_CLONE_USER,
        packageName = "user${settings.cloneUserId}",
        settings = settings,
        labels = listOf("检查 Root", "关闭分身用户"),
        script = "echo \"UCLONE_STAGE_BEGIN:COMMIT\"\n${ShellScripts.stopCloneUser(settings)}",
        report = report,
        requestId = requestId,
    )

    private suspend fun runScriptTask(
        type: TaskType,
        packageName: String,
        settings: UCloneSettings,
        labels: List<String>,
        script: String,
        report: (TaskProgress) -> Unit,
        requestId: String = newRequestId(),
        relatedTransactionId: String? = null,
    ): TaskRecord {
        val timestamp = System.currentTimeMillis()
        val logPath = "${settings.rootDir}/logs/${type.name.lowercase()}_${packageName}_$timestamp.log"
        var steps = labels.mapIndexed { index, label ->
            TaskStep(label, if (index == 0) StepStatus.RUNNING else StepStatus.PENDING)
        }
        val task = logStore.running(type, packageName, logPath, requestId)
        report(TaskProgress(task, steps, ""))
        val liveTail = LiveLogTail()
        val throttle = TaskProgressThrottle()
        var progressTask = task
        val progressLock = Any()
        val identityBoundScript = if (type in PACKAGE_IDENTITY_TASKS) {
            val certificateSha256 = signingIdentityProvider.certificateSha256(packageName)
            check(SIGNING_CERTIFICATE_SET.matches(certificateSha256)) {
                "无法确认 $packageName 的签名证书 SHA-256"
            }
            "UCLONE_EXPECTED_SIGNING_CERTIFICATE_SHA256=${shellQuote(certificateSha256)}\n$script"
        } else {
            script
        }
        val command = RootTaskScript.wrap(
            logPath = logPath,
            header = "TASK=$type\nREQUEST_ID=$requestId\nPACKAGE=$packageName\nSTART=$timestamp\nSTART_LOCAL=${formatLocalTime(timestamp)}\n",
            body = identityBoundScript,
            startedAt = timestamp,
            activeTask = ActiveRootTask(
                rootDir = settings.rootDir,
                requestId = requestId,
                taskType = type.name,
                packageName = packageName,
                startedAt = timestamp,
                recoveryTransactionId = relatedTransactionId,
            ),
        )
        val handleOutput: (ShellOutput) -> Unit = { output ->
            val line = if (output.stream == ShellStream.STDERR) "STDERR: ${output.line}" else output.line
            liveTail.append(line)
            var force = false
            synchronized(progressLock) {
                if (output.line.startsWith("ROOT=") && output.line.contains("uid=0")) {
                    steps = mark(steps, 0, StepStatus.SUCCESS, 1, StepStatus.RUNNING)
                    force = true
                }
                if (output.line.startsWith("UCLONE_STAGE_BEGIN:")) {
                    val stageName = output.line.substringAfter(':').trim()
                    runCatching { TaskStage.valueOf(stageName) }.getOrNull()?.let { stage ->
                        progressTask = progressTask.copy(currentStage = stage, message = stage.displayLabel)
                        force = true
                    }
                }
                if (throttle.shouldEmit(force)) {
                    report(TaskProgress(progressTask, steps, liveTail.value()))
                }
            }
        }
        val protectedInput = settings.cloneUnlockCredential
            .trim()
            .takeIf { it.isNotEmpty() && "IFS= read -r CLONE_UNLOCK_CREDENTIAL" in script }
            ?.plus("\n")
        val timeoutSeconds = taskHostTimeoutSeconds(type)
        val result = if (protectedInput == null) {
            shell.execStreaming(command, timeoutSeconds, handleOutput)
        } else {
            shell.execStreamingWithInput(command, protectedInput, timeoutSeconds, handleOutput)
        }
        val endedAt = System.currentTimeMillis()
        val liveLog = "OUTPUT:\n${result.stdout}\nSTDERR:\n${result.stderr}\nOUTPUT_TRUNCATED=${if (result.outputTruncated) 1 else 0}\nEXIT=${result.exitCode}\nEND=$endedAt\nEND_LOCAL=${formatLocalTime(endedAt)}\nDURATION_MS=${endedAt - timestamp}\n"
        val baseStatus = TaskOutcome.status(result)
        val status = if (result.outputTruncated && baseStatus == TaskStatus.SUCCESS) {
            TaskStatus.SUCCESS_WITH_WARNINGS
        } else {
            baseStatus
        }
        val rootUnavailable = RootTaskOutput.from(result).has("ERR_ROOT_UNAVAILABLE")
        steps = if (rootUnavailable) {
            failRemaining(steps, 0)
        } else if (status.isSuccessful) {
            steps.map { it.copy(status = StepStatus.SUCCESS) }
        } else {
            failRemaining(steps, steps.indexOfFirst { it.status == StepStatus.RUNNING }.coerceAtLeast(1))
        }
        val message = when (status) {
            TaskStatus.SUCCESS,
            TaskStatus.SUCCESS_WITH_WARNINGS,
            -> TaskResultMessages.successMessage(liveLog) + if (result.outputTruncated) "；运行输出过长，完整内容请查看任务日志" else ""
            else -> TaskResultMessages.fatalInstallMessage(liveLog)
                ?: TaskOutcome.failureMessage(status, result)
                ?: taskFailureMessage(result)
        }
        val metrics = TaskMetricsParser.parse(
            output = result.stdout + "\n" + result.stderr,
            rootProcessStarts = result.processStarts,
            rootCommandCount = 1,
        )
        val auditedTask = task.copy(
            audit = TaskAuditParser.enrich(task.audit, type, result.stdout + "\n" + result.stderr),
            outcomeCode = TaskOutcome.code(result, status),
        )
        val finished = logStore.finish(auditedTask, status, message, metrics)
        report(TaskProgress(finished, steps, liveLog))
        return finished
    }

    private fun taskFailureMessage(result: ShellResult): String {
        val events = RootTaskOutput.from(result)
        return when {
            events.has("ERR_ROOT_UNAVAILABLE") -> "Root 权限不可用"
            events.has("ERR_ACTIVE_ROOT_TASK") || events.has("ERR_ACTIVE_ROOT_TASK_INITIALIZING") ->
                "另一个 Root 数据任务仍在运行，请等待其结束"
            events.has("ERR_SELF_INSTALL") -> "不允许把 UClone 自身安装到另一用户"
            events.has("ERR_INSTALL_SOURCE_MISSING") -> "源用户未安装此 App，无法安装到另一侧"
            events.has("ERR_INSTALL_EXISTING_FAILED") -> "系统拒绝为目标用户启用此 App"
            events.has("ERR_INSTALL_VERIFY_TIMEOUT") -> "系统已接收安装请求，但未能确认目标用户安装"
            events.has("ERR_INSUFFICIENT_SPACE") -> "存储空间不足，任务未停止目标 App，也未写入数据"
            events.has("ERR_SPACE_UNKNOWN") || events.has("ERR_SPACE_ESTIMATE") -> "无法确认可用空间，任务未执行"
            events.has("ERR_CLONE_AUTO_UNLOCK_DISABLED") -> "分身未解锁，请开启分身自动解锁或先手动解锁"
            events.has("ERR_CLONE_PIN_MISSING") -> "请先在设置中填写分身锁屏 PIN/密码"
            events.first("ERR_CLONE_PIN_VERIFY_FAILED")?.contains(":BAD_CREDENTIAL") == true -> "分身 PIN 验证失败"
            events.first("ERR_CLONE_PIN_VERIFY_FAILED")?.contains(":THROTTLED") == true -> "PIN 验证被系统限流，请稍后再试"
            events.first("ERR_CLONE_PIN_VERIFY_FAILED")?.contains(":UNIFIED_CHALLENGE_UNSUPPORTED") == true ->
                "分身使用统一锁屏凭据，当前系统不支持后台验证"
            events.first("ERR_CLONE_PIN_VERIFY_FAILED")?.contains(":UNSUPPORTED") == true -> "当前系统不支持命令行验证分身 PIN"
            events.has("ERR_CLONE_UNLOCK_TIMEOUT") -> "分身解锁超时，请确认 PIN 和系统状态"
            events.has("ERR_PACKAGE_NOT_LISTED_TARGET") -> "分身系统未安装此 App，无法推送"
            events.has("ERR_PACKAGE_NOT_LISTED_SOURCE") -> "主系统未安装此 App，无法推送"
            events.has("ERR_PACKAGE_NOT_LISTED") -> "分身系统未安装此 App，未执行备份或切换"
            events.has("ERR_MAIN_STATE_BACKUP_MISSING") -> "缺少可返回的主系统数据备份，未更新切换状态"
            events.has("ERR_ACTIVE_RETURN_POINT_DELETE") -> "该备份是当前分身态返回主系统所必需的回滚点，请先将数据状态设为未知再删除"
            events.has("ERR_BACKUP_STATE_UNKNOWN") -> "旧备份没有主系统/分身来源标识，为避免状态错乱已停止恢复"
            events.has("ERR_PUSH_CE_MISSING") -> "主系统 CE 数据缺失，未执行推送"
            events.has("ERR_FORCE_STOP_FAILED") -> "无法停止分身 App，未读取或写入数据"
            events.has("ERR_UNTRUSTED_WORKSPACE") -> "当前保存路径不是受信任的 UClone 工作区，未修改任何文件"
            events.has("ERR_WORKSPACE_SYMLINK") || events.has("ERR_UNSAFE_WORKSPACE_TARGET") ->
                "工作区包含不安全的符号链接或路径，未执行归属修复"
            events.has("ERR_WORKSPACE_SCAN") -> "无法完整扫描工作区，未执行归属修复"
            events.has("ERR_WORKSPACE_OWNER_REMAINING") -> "部分文件归属未修复，可稍后重试"
            events.has("ERR_WORKSPACE_OWNER_REPAIR_FAILED") -> "备份容量归属修复失败，可安全重试"
            events.has("ERR_SOURCE_PERMISSION_CAPTURE") -> "无法可靠读取源 App 权限状态，任务未修改目标数据"
            events.has("ERR_TRANSACTION_PERMISSION_CAPTURE") -> "无法建立完整权限回滚点，任务未修改目标数据"
            events.has("ERR_PERMISSION_EXACT_RESTORE") -> "精确权限恢复失败，已进入事务回滚"
            events.has("ERR_GATE_CRITICAL_PACKAGE") -> "系统关键 App 不允许进入数据恢复门禁"
            events.has("ERR_GATE_SHARED_UID") -> "此 App 与其他包共享 UID，为避免并发访问数据，任务未执行"
            events.has("ERR_GATE_ENABLED_STATE") ||
                events.has("ERR_GATE_SUSPENDED_STATE") ||
                events.has("ERR_GATE_STOPPED_STATE") -> "当前系统无法可靠读取 App 启动状态，任务未修改数据"
            events.has("ERR_GATE_PROCESS_STILL_RUNNING") -> "无法完全停止目标 App 进程，任务未修改数据"
            events.has("ERR_GATE_STOPPED_BRIDGE_UNAVAILABLE") ||
                events.has("ERR_GATE_STOPPED_BRIDGE_RELEASE") ->
                "当前系统无法精确恢复 App 的 stopped 状态，任务未修改数据"
            events.has("ERR_GATE_ACQUIRE_ROLLBACK") -> "App 启动门禁未能恢复原状态，已进入安全恢复模式"
            events.has("ERR_SOURCE_GATE_ACQUIRE") -> "无法冻结源 App，未读取可能变化中的数据"
            events.has("ERR_TARGET_GATE_ACQUIRE") -> "无法冻结目标 App，任务未修改数据"
            events.has("ERR_SNAPSHOT_SIGNATURE_MISMATCH") -> "备份签名证书与当前 App 不一致，已禁止恢复"
            events.has("ERR_UNSAFE_BACKUP_MANIFEST") -> "备份清单路径不安全，已禁止读取和恢复"
            events.has("ERR_TRANSACTION_MANIFEST_MISSING_OR_UNSAFE") ->
                "操作前回滚清单缺失或路径不安全，目标 App 将保持冻结"
            events.has("ERR_SNAPSHOT_VERSION_CONFIRMATION_REQUIRED") -> "备份版本与当前 App 不一致，请在主 App 中确认跨版本恢复"
            events.has("ERR_LEGACY_PACKAGE_IDENTITY_CONFIRMATION_REQUIRED") -> "旧版备份缺少签名证书信息，请在主 App 中完成高风险确认"
            events.has("ERR_NOTHING_PUSHED") || events.has("ERR_NOTHING_COPIED") -> "没有找到可推送的数据"
            events.has("ERR_FORCE_UPDATE_CLONE_DATA") -> "分数据更新失败，主数据未还原；当前仍保持分身态"
            events.has("ERR_RESTORE_MAIN_AFTER_CLONE_UPDATE") -> "分数据已更新，但主数据还原失败；请查看任务日志"
            else -> result.stderr.ifBlank { "命令失败：${result.exitCode}" }
        }
    }

    private companion object {
        val ANDROID_PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
        val SAFE_ROLLBACK_ID = Regex("[A-Za-z0-9_.-]+")
        val SIGNING_CERTIFICATE_SET = Regex("[0-9a-f]{64}(?:,[0-9a-f]{64})*")
        val PACKAGE_IDENTITY_TASKS = setOf(
            TaskType.CAPTURE_SNAPSHOT_FROM_CLONE,
            TaskType.RESTORE_SNAPSHOT_TO_MAIN,
            TaskType.ROLLBACK_MAIN_DATA,
            TaskType.RESTORE_FROM_CLONE_LATEST,
            TaskType.SWITCH_TO_CLONE_STATE,
            TaskType.PUSH_MAIN_TO_CLONE,
            TaskType.RESTORE_CLONE_ROLLBACK_TO_CLONE,
            TaskType.RESTORE_SWITCH_MAIN_STATE,
            TaskType.INSTALL_AND_SYNC_TO_OTHER_USER,
            TaskType.RECOVER_INTERRUPTED_TRANSACTION,
        )
    }
    private fun formatLocalTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(millis))

    private fun mark(
        steps: List<TaskStep>,
        doneIndex: Int,
        doneStatus: StepStatus,
        nextIndex: Int,
        nextStatus: StepStatus,
    ): List<TaskStep> = steps.mapIndexed { index, step ->
        when (index) {
            doneIndex -> step.copy(status = doneStatus)
            nextIndex -> step.copy(status = nextStatus)
            else -> step
        }
    }

    private fun failRemaining(steps: List<TaskStep>, failedIndex: Int): List<TaskStep> =
        steps.mapIndexed { index, step ->
            when {
                index < failedIndex -> step.copy(status = StepStatus.SUCCESS)
                index == failedIndex -> step.copy(status = StepStatus.FAILED)
                else -> step.copy(status = StepStatus.PENDING)
            }
        }
}

internal fun taskHostTimeoutSeconds(type: TaskType): Long =
    if (type in TRANSACTIONAL_TASK_TYPES) 0 else 900

internal val TRANSACTIONAL_TASK_TYPES = setOf(
    TaskType.RESTORE_SNAPSHOT_TO_MAIN,
    TaskType.ROLLBACK_MAIN_DATA,
    TaskType.RESTORE_FROM_CLONE_LATEST,
    TaskType.SWITCH_TO_CLONE_STATE,
    TaskType.PUSH_MAIN_TO_CLONE,
    TaskType.RESTORE_CLONE_ROLLBACK_TO_CLONE,
    TaskType.RESTORE_SWITCH_MAIN_STATE,
    TaskType.REPAIR_WORKSPACE_OWNERSHIP,
    TaskType.INSTALL_AND_SYNC_TO_OTHER_USER,
    TaskType.RECOVER_INTERRUPTED_TRANSACTION,
)

private fun <K, V> Map<K, V>.getValueOrNull(key: K): V? = if (containsKey(key)) getValue(key) else null

private fun newRequestId(): String = UUID.randomUUID().toString()

internal fun restoreBackupListScript(rollbackRoot: String, switchRoot: String): String = """
    ROLLBACK_ROOT=${shellQuote(rollbackRoot)}
    SWITCH_ROOT=${shellQuote(switchRoot)}
    [ -d "${'$'}ROLLBACK_ROOT" ] || exit 0
    for d in "${'$'}ROLLBACK_ROOT"/*/*; do
      [ -d "${'$'}d" ] || continue
      [ -f "${'$'}d/manifest.json" ] || continue
      pkg=${'$'}(basename "${'$'}(dirname "${'$'}d")")
      id=${'$'}(basename "${'$'}d")
      ts=${'$'}(stat -c %Y "${'$'}d" 2>/dev/null || echo 0)
      size=${'$'}(du -skx "${'$'}d" 2>/dev/null | awk '{print ${'$'}1}')
      active=0
      if [ -f "${'$'}SWITCH_ROOT/${'$'}pkg/active" ] && [ "${'$'}(sed -n '1p' "${'$'}SWITCH_ROOT/${'$'}pkg/active" | tr -d '\r')" = "${'$'}id" ]; then
        active=1
      fi
      reason=${'$'}(sed -n 's/.*"reason":"\([^"]*\)".*/\1/p' "${'$'}d/manifest.json" | head -1)
      state_kind=${'$'}(sed -n 's/.*"stateKind":"\([^"]*\)".*/\1/p' "${'$'}d/manifest.json" | head -1)
      if [ -z "${'$'}reason" ]; then
        case "${'$'}id" in
          rollback_*) reason="恢复主系统备份前生成" ;;
          *) if [ "${'$'}active" = "1" ]; then reason="切换到分身态前生成"; else reason="恢复或切换前生成"; fi ;;
        esac
      fi
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "${'$'}pkg" "${'$'}id" "${'$'}ts" "${'$'}size" "${'$'}active" "${'$'}reason" "${'$'}state_kind"
    done
""".trimIndent()
