package com.uclone.restore.data

import android.content.Context
import com.uclone.restore.model.UCloneSettings

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("uclone_settings", Context.MODE_PRIVATE)

    fun load(): UCloneSettings = UCloneSettings(
        mainUserId = prefs.getInt("mainUserId", 0),
        cloneUserId = prefs.getInt("cloneUserId", 10),
        rootDir = prefs.getString("rootDir", "/data/adb/uclone") ?: "/data/adb/uclone",
        includeCe = prefs.getBoolean("includeCe", true),
        includeDe = prefs.getBoolean("includeDe", true),
        includeExternal = prefs.getBoolean("includeExternal", false),
        includeMedia = prefs.getBoolean("includeMedia", false),
        includeObb = prefs.getBoolean("includeObb", false),
        includePermissions = prefs.getBoolean("includePermissions", true),
        excludeCache = prefs.getBoolean("excludeCache", true),
        favoritePackages = prefs.getStringSet("favoritePackages", emptySet()).orEmpty().toSet(),
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
            .putStringSet("favoritePackages", settings.favoritePackages.toMutableSet())
            .apply()
    }
}
