package com.gkeyes.uclone.crossuserprobe

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ProbeIdentity {
    const val PER_USER_RANGE = 100000
    private const val CE_MARKER_FILE = "CE_MARKER.txt"
    private const val DE_MARKER_FILE = "DE_MARKER.txt"

    fun userIdFromUid(uid: Int): Int = uid / PER_USER_RANGE

    fun appIdFromUid(uid: Int): Int = uid % PER_USER_RANGE

    fun markerText(userId: Int, storage: StorageKind): String = when (storage) {
        StorageKind.CE -> "user$userId-ce"
        StorageKind.DE -> "user$userId-de"
    }

    fun writeMarker(context: Context, storage: StorageKind): String {
        val userId = userIdFromUid(Process.myUid())
        val file = markerFile(context, storage)
        file.parentFile?.mkdirs()
        file.writeText(markerText(userId, storage))
        return file.absolutePath
    }

    fun readMarker(context: Context, storage: StorageKind): String =
        markerFile(context, storage).takeIf { it.isFile }?.readText()?.trim().orEmpty().ifBlank { "absent" }

    fun collect(activity: Activity): List<Pair<String, String>> {
        val context = activity.applicationContext
        val uid = Process.myUid()
        val userManager = context.getSystemService(UserManager::class.java)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val displayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.displayId ?: -1
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.displayId
        }
        val deviceProtected = context.createDeviceProtectedStorageContext()
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        return listOf(
            "packageName" to context.packageName,
            "processName" to currentProcessName(context),
            "pid" to Process.myPid().toString(),
            "uid" to uid.toString(),
            "appId" to appIdFromUid(uid).toString(),
            "userId" to userIdFromUid(uid).toString(),
            "displayId" to displayId.toString(),
            "taskId" to activity.taskId.toString(),
            "dataDir" to context.applicationInfo.dataDir,
            "deviceProtectedDataDir" to deviceProtected.dataDir.absolutePath,
            "isUserUnlocked" to userManager.isUserUnlocked.toString(),
            "isDeviceProtectedStorage" to context.isDeviceProtectedStorage.toString(),
            "orientation" to context.resources.configuration.orientation.toString(),
            "windowingMode" to "pending_activity_task_probe",
            "ceMarker" to readMarker(context, StorageKind.CE),
            "deMarker" to readMarker(context, StorageKind.DE),
            "installerPackageName" to (installer ?: "unknown"),
            "hasTouchscreen" to context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN).toString(),
            "memoryClass" to activityManager.memoryClass.toString(),
            "timestamp" to utcNow(),
            "buildFingerprint" to Build.FINGERPRINT,
        )
    }

    private fun markerFile(context: Context, storage: StorageKind): File {
        val base = when (storage) {
            StorageKind.CE -> context
            StorageKind.DE -> context.createDeviceProtectedStorageContext()
        }
        return File(base.filesDir, when (storage) {
            StorageKind.CE -> CE_MARKER_FILE
            StorageKind.DE -> DE_MARKER_FILE
        })
    }

    private fun currentProcessName(context: Context): String {
        return android.app.Application.getProcessName()
    }

    private fun utcNow(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}

enum class StorageKind {
    CE,
    DE,
}
