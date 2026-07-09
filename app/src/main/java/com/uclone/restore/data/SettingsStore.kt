package com.uclone.restore.data

import android.content.Context
import com.uclone.restore.model.UCloneSettings

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("uclone_settings", Context.MODE_PRIVATE)
    private val schemaVersion = 8

    fun load(): UCloneSettings = UCloneSettings(
        mainUserId = prefs.getInt("mainUserId", 0),
        cloneUserId = prefs.getInt("cloneUserId", 10),
        rootDir = prefs.getString("rootDir", "/data/adb/uclone") ?: "/data/adb/uclone",
        includeCe = prefs.getBoolean("includeCe", true),
        includeDe = prefs.getBoolean("includeDe", true),
        includeExternal = prefs.getBoolean("includeExternal", true),
        includeMedia = prefs.getBoolean("includeMedia", false),
        includeObb = prefs.getBoolean("includeObb", false),
        includePermissions = prefs.getBoolean("includePermissions", true),
        excludeCache = prefs.getBoolean("excludeCache", true),
        stopCloneAfterTask = prefs.getBoolean("stopCloneAfterTask", true),
        autoUnlockClone = prefs.getBoolean("autoUnlockClone", false),
        allowModuleControl = prefs.getBoolean("allowModuleControl", false),
        favoritePackages = prefs.getStringSet("favoritePackages", emptySet()).orEmpty().toSet(),
        cloneUnlockCredential = prefs.getString("cloneUnlockCredential", "") ?: "",
    )

    fun save(settings: UCloneSettings) {
        prefs.edit()
            .putInt("mainUserId", settings.mainUserId)
            .putInt("cloneUserId", settings.cloneUserId)
            .putString("rootDir", settings.rootDir)
            .putBoolean("includeCe", settings.includeCe)
            .putBoolean("includeDe", settings.includeDe)
            .putBoolean("includeExternal", settings.includeExternal)
            .putBoolean("includeMedia", settings.includeMedia)
            .putBoolean("includeObb", settings.includeObb)
            .putBoolean("includePermissions", settings.includePermissions)
            .putBoolean("excludeCache", settings.excludeCache)
            .putBoolean("stopCloneAfterTask", settings.stopCloneAfterTask)
            .putBoolean("autoUnlockClone", settings.autoUnlockClone)
            .putBoolean("allowModuleControl", settings.allowModuleControl)
            .putStringSet("favoritePackages", settings.favoritePackages.toMutableSet())
            .putString("cloneUnlockCredential", settings.cloneUnlockCredential.trim())
            .putInt("settingsSchemaVersion", schemaVersion)
            .apply()
    }
}
