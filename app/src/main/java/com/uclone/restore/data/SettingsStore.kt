package com.uclone.restore.data

import android.content.Context
import com.uclone.restore.model.PermissionRestoreMode
import com.uclone.restore.model.UCloneSettings

class SettingsStore private constructor(
    context: Context,
    private val credentialCipher: CredentialCipher,
) {
    constructor(context: Context) : this(context, AndroidKeystoreCredentialCipher())

    private val prefs = context.getSharedPreferences("uclone_settings", Context.MODE_PRIVATE)
    private val schemaVersion = 12

    fun load(): UCloneSettings = SettingsValidation.sanitizedForLoad(UCloneSettings(
        mainUserId = prefs.getInt("mainUserId", 0),
        cloneUserId = prefs.getInt("cloneUserId", 10),
        rootDir = prefs.getString("rootDir", "/data/adb/uclone") ?: "/data/adb/uclone",
        includeCe = prefs.getBoolean("includeCe", true),
        includeDe = prefs.getBoolean("includeDe", true),
        includeExternal = prefs.getBoolean("includeExternal", true),
        includeMedia = prefs.getBoolean("includeMedia", false),
        includeObb = prefs.getBoolean("includeObb", false),
        includePermissions = prefs.getBoolean("includePermissions", true),
        permissionRestoreMode = prefs.getString("permissionRestoreMode", PermissionRestoreMode.MERGE.name)
            ?.let { value -> PermissionRestoreMode.entries.firstOrNull { it.name == value } }
            ?: PermissionRestoreMode.MERGE,
        excludeCache = prefs.getBoolean("excludeCache", true),
        stopCloneAfterTask = prefs.getBoolean("stopCloneAfterTask", true),
        autoUnlockClone = prefs.getBoolean("autoUnlockClone", false),
        allowModuleControl = prefs.getBoolean("allowModuleControl", false),
        allowSystemAppInstall = prefs.getBoolean("allowSystemAppInstall", false),
        reuseExistingPassiveBackups = prefs.getBoolean("reuseExistingPassiveBackups", false),
        forceUpdateCloneDataBeforeMainRestore = prefs.getBoolean("forceUpdateCloneDataBeforeMainRestore", false),
        favoritePackages = prefs.getStringSet("favoritePackages", emptySet()).orEmpty().toSet(),
        cloneUnlockCredential = loadCredential(),
    ))

    fun save(settings: UCloneSettings) {
        val normalized = SettingsValidation.requireValid(settings)
        prefs.edit()
            .putInt("mainUserId", normalized.mainUserId)
            .putInt("cloneUserId", normalized.cloneUserId)
            .putString("rootDir", normalized.rootDir)
            .putBoolean("includeCe", normalized.includeCe)
            .putBoolean("includeDe", normalized.includeDe)
            .putBoolean("includeExternal", normalized.includeExternal)
            .putBoolean("includeMedia", normalized.includeMedia)
            .putBoolean("includeObb", normalized.includeObb)
            .putBoolean("includePermissions", normalized.includePermissions)
            .putString("permissionRestoreMode", normalized.permissionRestoreMode.name)
            .putBoolean("excludeCache", normalized.excludeCache)
            .putBoolean("stopCloneAfterTask", normalized.stopCloneAfterTask)
            .putBoolean("autoUnlockClone", normalized.autoUnlockClone)
            .putBoolean("allowModuleControl", normalized.allowModuleControl)
            .putBoolean("allowSystemAppInstall", normalized.allowSystemAppInstall)
            .putBoolean("reuseExistingPassiveBackups", normalized.reuseExistingPassiveBackups)
            .putBoolean("forceUpdateCloneDataBeforeMainRestore", normalized.forceUpdateCloneDataBeforeMainRestore)
            .putStringSet("favoritePackages", normalized.favoritePackages.toMutableSet())
            .putString(ENCRYPTED_CREDENTIAL_KEY, encryptCredential(normalized.cloneUnlockCredential))
            .remove(LEGACY_CREDENTIAL_KEY)
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
