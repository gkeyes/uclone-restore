package com.uclone.restore.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.SystemClock
import androidx.core.graphics.drawable.toBitmap
import com.uclone.restore.MainActivity
import com.uclone.restore.R
import com.uclone.restore.sync.AppDataState

data class FavoriteShortcutEntry(
    val packageName: String,
    val label: String,
    val dataState: AppDataState,
)

data class LauncherShortcutRequest(
    val packageName: String,
    val openDetailsOnly: Boolean = false,
    val nonce: Long = SystemClock.uptimeMillis(),
) {
    companion object {
        fun isShortcutIntent(intent: Intent?): Boolean =
            intent?.action == LauncherShortcutController.ACTION_TOGGLE_FAVORITE

        fun isOpenDetailsIntent(intent: Intent?): Boolean =
            intent?.action == LauncherShortcutController.ACTION_OPEN_FAVORITE_DETAILS

        fun fromIntent(intent: Intent?, expectedToken: String): LauncherShortcutRequest? {
            if (!isShortcutIntent(intent) && !isOpenDetailsIntent(intent)) return null
            val sourceIntent = intent ?: return null
            val token = sourceIntent.getStringExtra(LauncherShortcutController.EXTRA_TOKEN)
            if (!isTrustedLauncherShortcutToken(token, expectedToken)) return null
            val packageName = sourceIntent.getStringExtra(LauncherShortcutController.EXTRA_PACKAGE_NAME)
                ?.takeIf(String::isNotBlank)
                ?: return null
            return LauncherShortcutRequest(
                packageName = packageName,
                openDetailsOnly = isOpenDetailsIntent(sourceIntent),
            )
        }
    }
}

internal fun isTrustedLauncherShortcutToken(providedToken: String?, expectedToken: String): Boolean =
    !providedToken.isNullOrBlank() && expectedToken.isNotBlank() && providedToken == expectedToken

internal fun nextShortcutRefreshGeneration(current: Long): Long =
    if (current <= 0L || current == Long.MAX_VALUE) 1L else current + 1L

internal fun canClearShortcutRefreshGeneration(expected: Long, current: Long): Boolean =
    expected > 0L && expected == current

internal val AppDataState.launcherShortcutActionLabel: String
    get() = when (this) {
        AppDataState.Main -> "切换"
        is AppDataState.Clone -> "还原"
        AppDataState.Unknown -> "检查状态"
    }

class LauncherShortcutController(private val context: Context) {
    private val preferences
        get() = context.getSharedPreferences("uclone_settings", Context.MODE_PRIVATE)

    private val shortcutManager: ShortcutManager?
        get() = context.getSystemService(ShortcutManager::class.java)

    val pendingStateRefreshGeneration: Long
        get() = synchronized(STATE_REFRESH_MONITOR) { readPendingStateRefreshGeneration() }

    fun markStateRefreshPending(): Long = synchronized(STATE_REFRESH_MONITOR) {
        val lastAssigned = preferences.getLong(STATE_REFRESH_SEQUENCE_PREF_KEY, 0L)
        val currentPending = readPendingStateRefreshGeneration()
        val next = nextShortcutRefreshGeneration(maxOf(lastAssigned, currentPending))
        if (
            preferences.edit()
                .putLong(STATE_REFRESH_SEQUENCE_PREF_KEY, next)
                .putLong(STATE_REFRESH_PENDING_GENERATION_PREF_KEY, next)
                .remove(LEGACY_STATE_REFRESH_PENDING_PREF_KEY)
                .commit()
        ) {
            next
        } else {
            currentPending
        }
    }

    fun clearStateRefreshPending(expectedGeneration: Long): Boolean = synchronized(STATE_REFRESH_MONITOR) {
        val current = readPendingStateRefreshGeneration()
        if (!canClearShortcutRefreshGeneration(expectedGeneration, current)) {
            false
        } else {
            preferences.edit()
                .remove(STATE_REFRESH_PENDING_GENERATION_PREF_KEY)
                .remove(LEGACY_STATE_REFRESH_PENDING_PREF_KEY)
                .commit()
        }
    }

    /**
     * Publishes only if this refresh still owns the latest pending state generation.
     * Keeping the generation check and ShortcutManager publication in one monitor prevents a
     * completed older task from overwriting a newer task's launcher state.
     */
    fun updateFavoriteShortcutsIfCurrent(
        entries: List<FavoriteShortcutEntry>,
        expectedGeneration: Long,
    ): Boolean = synchronized(STATE_REFRESH_MONITOR) {
        if (!canClearShortcutRefreshGeneration(expectedGeneration, readPendingStateRefreshGeneration())) {
            return@synchronized false
        }
        updateFavoriteShortcutsLocked(entries)
        clearStateRefreshPending(expectedGeneration)
    }

    private fun readPendingStateRefreshGeneration(): Long {
        val pending = preferences.getLong(STATE_REFRESH_PENDING_GENERATION_PREF_KEY, 0L)
        if (pending > 0L) return pending
        if (!preferences.getBoolean(LEGACY_STATE_REFRESH_PENDING_PREF_KEY, false)) return 0L

        val migrated = nextShortcutRefreshGeneration(
            preferences.getLong(STATE_REFRESH_SEQUENCE_PREF_KEY, 0L),
        )
        preferences.edit()
            .putLong(STATE_REFRESH_SEQUENCE_PREF_KEY, migrated)
            .putLong(STATE_REFRESH_PENDING_GENERATION_PREF_KEY, migrated)
            .remove(LEGACY_STATE_REFRESH_PENDING_PREF_KEY)
            .commit()
        return migrated
    }

    fun updateFavoriteShortcuts(entries: List<FavoriteShortcutEntry>) = synchronized(STATE_REFRESH_MONITOR) {
        updateFavoriteShortcutsLocked(entries)
    }

    private fun updateFavoriteShortcutsLocked(entries: List<FavoriteShortcutEntry>) {
        val manager = shortcutManager ?: return
        val maxCount = manager.maxShortcutCountPerActivity.coerceAtMost(MAX_FAVORITE_SHORTCUTS)
        if (maxCount <= 0 || entries.isEmpty()) {
            manager.removeAllDynamicShortcuts()
            return
        }
        val shortcuts = entries
            .take(maxCount)
            .mapIndexed { index, entry -> entry.toShortcut(index) }
        manager.setDynamicShortcuts(shortcuts)
    }

    private fun FavoriteShortcutEntry.toShortcut(rank: Int): ShortcutInfo {
        val actionLabel = dataState.launcherShortcutActionLabel
        val opensDetails = dataState == AppDataState.Unknown
        return ShortcutInfo.Builder(context, shortcutId(packageName))
            .setShortLabel(compactLabel("$actionLabel $label"))
            .setLongLabel("$actionLabel $label")
            .setIcon(shortcutIcon())
            .setIntent(
                Intent(
                    context,
                    if (opensDetails) MainActivity::class.java else LauncherShortcutActionActivity::class.java,
                ).apply {
                    action = if (opensDetails) ACTION_OPEN_FAVORITE_DETAILS else ACTION_TOGGLE_FAVORITE
                    putExtra(EXTRA_PACKAGE_NAME, packageName)
                    putExtra(EXTRA_TOKEN, shortcutToken())
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            .setRank(rank)
            .build()
    }

    private fun compactLabel(value: String): String =
        if (value.length <= SHORT_LABEL_MAX) value else value.take(SHORT_LABEL_MAX - 1) + "…"

    private fun shortcutId(packageName: String): String = "favorite_toggle_$packageName"

    fun shortcutToken(): String =
        preferences.let { prefs ->
                prefs.getString(TOKEN_PREF_KEY, "")?.takeIf(String::isNotBlank)
                    ?: java.util.UUID.randomUUID().toString().also { generated ->
                        prefs.edit().putString(TOKEN_PREF_KEY, generated).apply()
                    }
            }

    private fun FavoriteShortcutEntry.shortcutIcon(): Icon = runCatching {
        Icon.createWithBitmap(
            context.packageManager
                .getApplicationIcon(packageName)
                .toBitmap(SHORTCUT_ICON_SIZE_PX, SHORTCUT_ICON_SIZE_PX),
        )
    }.getOrElse {
        Icon.createWithResource(context, R.mipmap.ic_launcher)
    }

    companion object {
        const val ACTION_TOGGLE_FAVORITE = "com.uclone.restore.action.TOGGLE_FAVORITE"
        const val ACTION_OPEN_FAVORITE_DETAILS = "com.uclone.restore.action.OPEN_FAVORITE_DETAILS"
        const val EXTRA_PACKAGE_NAME = "com.uclone.restore.extra.PACKAGE_NAME"
        const val EXTRA_TOKEN = "com.uclone.restore.extra.LAUNCHER_TOKEN"
        private const val TOKEN_PREF_KEY = "launcherShortcutToken"
        private const val STATE_REFRESH_SEQUENCE_PREF_KEY = "launcherShortcutStateRefreshSequence"
        private const val STATE_REFRESH_PENDING_GENERATION_PREF_KEY =
            "launcherShortcutStateRefreshPendingGeneration"
        private const val LEGACY_STATE_REFRESH_PENDING_PREF_KEY = "launcherShortcutStateRefreshPending"
        private val STATE_REFRESH_MONITOR = Any()
        private const val MAX_FAVORITE_SHORTCUTS = 4
        private const val SHORT_LABEL_MAX = 12
        private const val SHORTCUT_ICON_SIZE_PX = 192
    }
}
