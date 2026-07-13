package com.uclone.restore.sync

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.uclone.restore.model.AppEntry
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.ShellResult
import com.uclone.restore.util.RiskClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PackageInspector(
    private val context: Context,
    private val shell: RootShellExecutor,
) {
    suspend fun listApps(settings: UCloneSettings): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val user0 = PackageListParser.requirePackages(
            settings.mainUserId,
            shell.exec("cmd package list packages -U --user ${settings.mainUserId}", 45),
        )
        val user10 = PackageListParser.requirePackages(
            settings.cloneUserId,
            shell.exec("cmd package list packages -U --user ${settings.cloneUserId}", 45),
        )
        val metadataFlags = PackageManager.GET_META_DATA or PackageManager.MATCH_UNINSTALLED_PACKAGES
        val localApps = pm.getInstalledApplications(metadataFlags).associateBy { it.packageName }
        val packageNames = (user0.keys + user10.keys).ifEmpty { localApps.keys }
        packageNames
            .map { packageName ->
                val app = localApps[packageName] ?: runCatching {
                    pm.getApplicationInfo(packageName, metadataFlags)
                }.getOrNull()
                toEntry(pm, packageName, app, user0, user10)
            }
            .sortedBy { it.label.lowercase() }
    }

    suspend fun inspect(packageName: String, settings: UCloneSettings): AppEntry? =
        listApps(settings).firstOrNull { it.packageName == packageName }

    private fun toEntry(
        pm: PackageManager,
        packageName: String,
        app: ApplicationInfo?,
        user0: Map<String, Int?>,
        user10: Map<String, Int?>,
    ): AppEntry {
        val label = app?.loadLabel(pm)?.toString()?.ifBlank { packageName } ?: packageName
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        val system = app != null && app.flags and systemFlags != 0
        return AppEntry(
            packageName = packageName,
            label = label,
            user0Installed = user0.containsKey(packageName),
            user10Installed = user10.containsKey(packageName),
            user0Uid = user0[packageName],
            user10Uid = user10[packageName],
            isSystemApp = system,
            riskLevel = RiskClassifier.classify(packageName, label, system),
            lastSnapshotAt = null,
            snapshotSizeKb = null,
            lastRestoreAt = null,
        )
    }
}

internal object PackageListParser {
    fun requirePackages(userId: Int, result: ShellResult): Map<String, Int?> {
        if (!result.isSuccess) {
            val detail = result.stderr.lineSequence().map(String::trim).firstOrNull(String::isNotBlank)
                ?: result.stdout.lineSequence().map(String::trim).firstOrNull(String::isNotBlank)
                ?: "exit=${result.exitCode}"
            throw IllegalStateException("读取 user$userId App 列表失败：$detail")
        }
        if (result.outputTruncated) {
            throw IllegalStateException("读取 user$userId App 列表失败：命令输出被截断")
        }
        val packages = parse(result.stdout)
        if (packages.isEmpty()) {
            throw IllegalStateException("读取 user$userId App 列表失败：命令返回为空")
        }
        return packages
    }

    private fun parse(output: String): Map<String, Int?> {
        val result = linkedMapOf<String, Int?>()
        output.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("package:") }
            .forEach { line ->
                val parts = line.split(" ")
                val pkg = parts.first().removePrefix("package:")
                val uid = parts.firstOrNull { it.startsWith("uid:") }?.removePrefix("uid:")?.toIntOrNull()
                result[pkg] = uid
            }
        return result
    }
}
