package com.uclone.restore.sync

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.uclone.restore.model.AppEntry
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.util.RiskClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PackageInspector(
    private val context: Context,
    private val shell: RootShellExecutor,
) {
    suspend fun listApps(settings: UCloneSettings): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val user0 = parsePackageUidList(shell.exec("cmd package list packages -U --user ${settings.mainUserId}", 45).stdout)
        val user10 = parsePackageUidList(shell.exec("cmd package list packages -U --user ${settings.cloneUserId}", 45).stdout)
        val metadataFlags = PackageManager.GET_META_DATA or PackageManager.MATCH_UNINSTALLED_PACKAGES
        val localApps = pm.getInstalledApplications(metadataFlags).associateBy { it.packageName }
        (user0.keys + user10.keys)
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

internal fun parsePackageUidList(output: String): Map<String, Int?> {
    val result = linkedMapOf<String, Int?>()
    output.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("package:") }
        .forEach { line ->
            val parts = line.split(" ")
            val packageName = parts.first().removePrefix("package:")
            val uid = parts.firstOrNull { it.startsWith("uid:") }?.removePrefix("uid:")?.toIntOrNull()
            result[packageName] = uid
        }
    return result
}
