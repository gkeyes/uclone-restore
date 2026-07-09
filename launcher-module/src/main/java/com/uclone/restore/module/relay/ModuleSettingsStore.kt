package com.uclone.restore.module.relay

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ModuleSettingsStore {
    private const val MAX_LOG_LINES = 80

    fun isHookEnabled(context: Context): Boolean {
        val prefs = prefs(context)
        return prefs.getBoolean(ModuleConstants.KEY_HOOK_ENABLED, true) &&
            !prefs.getBoolean(ModuleConstants.KEY_HOOK_AUTO_DISABLED, false)
    }

    fun setHookEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(ModuleConstants.KEY_HOOK_ENABLED, enabled)
            .putBoolean(ModuleConstants.KEY_HOOK_AUTO_DISABLED, false)
            .putInt(ModuleConstants.KEY_CONSECUTIVE_HOOK_ERRORS, 0)
            .apply()
    }

    fun allowedLaunchers(context: Context): Set<String> =
        csvSet(
            prefs(context).getString(
                ModuleConstants.KEY_ALLOWED_LAUNCHERS,
                ModuleConstants.DEFAULT_ALLOWED_LAUNCHERS,
            ).orEmpty(),
        )

    fun setAllowedLaunchers(context: Context, value: String) {
        prefs(context).edit().putString(ModuleConstants.KEY_ALLOWED_LAUNCHERS, value).apply()
    }

    fun allowedPackageText(context: Context): String =
        prefs(context).getString(ModuleConstants.KEY_ALLOWED_PACKAGES, "").orEmpty()

    fun setAllowedPackageText(context: Context, value: String) {
        prefs(context).edit().putString(ModuleConstants.KEY_ALLOWED_PACKAGES, value).apply()
    }

    fun isPackageAllowed(context: Context, packageName: String): Boolean =
        packageName in csvSet(allowedPackageText(context))

    fun recordHookEvent(context: Context, message: String, isError: Boolean) {
        val prefs = prefs(context)
        val nextErrors = if (isError) {
            prefs.getInt(ModuleConstants.KEY_CONSECUTIVE_HOOK_ERRORS, 0) + 1
        } else {
            0
        }
        val autoDisabled = nextErrors >= ModuleConstants.HOOK_ERROR_DISABLE_THRESHOLD
        prefs.edit()
            .putInt(ModuleConstants.KEY_CONSECUTIVE_HOOK_ERRORS, nextErrors)
            .putBoolean(ModuleConstants.KEY_HOOK_AUTO_DISABLED, autoDisabled)
            .putString(ModuleConstants.KEY_HOOK_EVENTS, appendLine(prefs.getString(ModuleConstants.KEY_HOOK_EVENTS, ""), message))
            .apply()
    }

    fun recordRelayEvent(context: Context, message: String) {
        val prefs = prefs(context)
        prefs.edit()
            .putString(ModuleConstants.KEY_RELAY_EVENTS, appendLine(prefs.getString(ModuleConstants.KEY_RELAY_EVENTS, ""), message))
            .apply()
    }

    fun hookEvents(context: Context): String = prefs(context).getString(ModuleConstants.KEY_HOOK_EVENTS, "").orEmpty()

    fun relayEvents(context: Context): String = prefs(context).getString(ModuleConstants.KEY_RELAY_EVENTS, "").orEmpty()

    fun resetAutoDisable(context: Context) {
        prefs(context).edit()
            .putInt(ModuleConstants.KEY_CONSECUTIVE_HOOK_ERRORS, 0)
            .putBoolean(ModuleConstants.KEY_HOOK_AUTO_DISABLED, false)
            .apply()
    }

    fun isAutoDisabled(context: Context): Boolean =
        prefs(context).getBoolean(ModuleConstants.KEY_HOOK_AUTO_DISABLED, false)

    fun consecutiveHookErrors(context: Context): Int =
        prefs(context).getInt(ModuleConstants.KEY_CONSECUTIVE_HOOK_ERRORS, 0)

    private fun prefs(context: Context) =
        context.getSharedPreferences(ModuleConstants.PREFS_NAME, Context.MODE_PRIVATE)

    private fun csvSet(value: String): Set<String> =
        value.split(',', '\n', ';', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun appendLine(old: String?, line: String): String {
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        return ((old.orEmpty().lines().filter { it.isNotBlank() } + "$stamp $line").takeLast(MAX_LOG_LINES))
            .joinToString("\n")
    }
}
