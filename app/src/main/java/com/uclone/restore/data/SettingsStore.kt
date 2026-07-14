package com.uclone.restore.data

import android.content.Context
import com.uclone.restore.model.CloneSessionPolicy
import com.uclone.restore.model.MainReturnPointPolicy
import com.uclone.restore.model.SwitchSafetyMode
import com.uclone.restore.model.UCloneSettings

class SettingsStore private constructor(
    context: Context,
    private val credentialCipher: CredentialCipher,
) {
    constructor(context: Context) : this(context, AndroidKeystoreCredentialCipher())

    private val prefs = context.getSharedPreferences("uclone_settings", Context.MODE_PRIVATE)
    private val schemaVersion = 13

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
        mainReturnPointPolicy = migratedMainReturnPointPolicy(prefs.getString("mainReturnPointPolicy", null)),
        cloneSessionPolicy = migratedCloneSessionPolicy(prefs.getString("cloneSessionPolicy", null)),
        switchSafetyMode = migratedSwitchSafetyMode(prefs.getString("switchSafetyMode", null)),
        favoritePackages = prefs.getStringSet("favoritePackages", emptySet()).orEmpty().toSet(),
        cloneUnlockCredential = loadCredential(),
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
            .putString("mainReturnPointPolicy", settings.mainReturnPointPolicy.name)
            .putString("cloneSessionPolicy", settings.cloneSessionPolicy.name)
            .putString("switchSafetyMode", settings.switchSafetyMode.name)
            .putStringSet("favoritePackages", settings.favoritePackages.toMutableSet())
            .putString(ENCRYPTED_CREDENTIAL_KEY, encryptCredential(settings.cloneUnlockCredential.trim()))
            .remove(LEGACY_CREDENTIAL_KEY)
            .remove("reuseExistingPassiveBackups")
            .remove("syncCloneDataBeforeMainRestore")
            .remove("forceUpdateCloneDataBeforeMainRestore")
            .putInt("settingsSchemaVersion", schemaVersion)
            .apply()
    }

    private fun loadCredential(): String {
        val encrypted = prefs.getString(ENCRYPTED_CREDENTIAL_KEY, null)
        if (!encrypted.isNullOrBlank()) {
            return runCatching { credentialCipher.decrypt(encrypted) }.getOrDefault("")
        }
        val legacy = prefs.getString(LEGACY_CREDENTIAL_KEY, null)?.trim().orEmpty()
        if (legacy.isEmpty()) return ""
        val migrated = runCatching { credentialCipher.encrypt(legacy) }.getOrNull()
        prefs.edit()
            .remove(LEGACY_CREDENTIAL_KEY)
            .apply {
                if (migrated != null) putString(ENCRYPTED_CREDENTIAL_KEY, migrated)
            }
            .commit()
        return if (migrated == null) "" else legacy
    }

    private fun encryptCredential(credential: String): String =
        credential.takeIf(String::isNotEmpty)?.let(credentialCipher::encrypt).orEmpty()

    private companion object {
        const val LEGACY_CREDENTIAL_KEY = "cloneUnlockCredential"
        const val ENCRYPTED_CREDENTIAL_KEY = "cloneUnlockCredentialEncrypted"
    }
}

internal fun migratedSwitchSafetyMode(storedValue: String?): SwitchSafetyMode =
    runCatching { storedValue?.let(SwitchSafetyMode::valueOf) }
        .getOrNull()
        ?: SwitchSafetyMode.SAFE

internal fun migratedMainReturnPointPolicy(storedValue: String?): MainReturnPointPolicy =
    runCatching { storedValue?.let(MainReturnPointPolicy::valueOf) }
        .getOrNull()
        ?: MainReturnPointPolicy.FIXED

internal fun migratedCloneSessionPolicy(storedValue: String?): CloneSessionPolicy =
    runCatching { storedValue?.let(CloneSessionPolicy::valueOf) }
        .getOrNull()
        ?: CloneSessionPolicy.SYNC_TO_CLONE_USER
