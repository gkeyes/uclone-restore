package com.uclone.restore.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.SystemClock
import androidx.core.graphics.drawable.toBitmap
import com.uclone.restore.R
import com.uclone.restore.sync.AppDataState

data class FavoriteShortcutEntry(
    val packageName: String,
    val label: String,
    val dataState: AppDataState,
)

data class LauncherShortcutRequest(
    val packageName: String,
    val nonce: Long = SystemClock.uptimeMillis(),
) {
    companion object {
        fun isShortcutIntent(intent: Intent?): Boolean =
            intent?.action == LauncherShortcutController.ACTION_TOGGLE_FAVORITE

        fun fromIntent(intent: Intent?, expectedToken: String): LauncherShortcutRequest? {
            if (!isShortcutIntent(intent)) return null
            val sourceIntent = intent ?: return null
            val token = sourceIntent.getStringExtra(LauncherShortcutController.EXTRA_TOKEN)
            if (!isTrustedLauncherShortcutToken(token, expectedToken)) return null
            val packageName = sourceIntent.getStringExtra(LauncherShortcutController.EXTRA_PACKAGE_NAME)
                ?.takeIf(String::isNotBlank)
                ?: return null
            return LauncherShortcutRequest(packageName)
        }
    }
}

internal fun isTrustedLauncherShortcutToken(providedToken: String?, expectedToken: String): Boolean =
    !providedToken.isNullOrBlank() && expectedToken.isNotBlank() && providedToken == expectedToken

class LauncherShortcutController(private val context: Context) {
    private val shortcutManager: ShortcutManager?
        get() = context.getSystemService(ShortcutManager::class.java)

    fun updateFavoriteShortcuts(entries: List<FavoriteShortcutEntry>) {
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
        val actionLabel = when (dataState) {
            AppDataState.Main -> "切换"
            is AppDataState.Clone -> "还原"
            AppDataState.Unknown -> "检查状态"
        }
        return ShortcutInfo.Builder(context, shortcutId(packageName))
            .setShortLabel(compactLabel("$actionLabel $label"))
            .setLongLabel("$actionLabel $label")
            .setIcon(shortcutIcon())
            .setIntent(
                Intent(context, LauncherShortcutActionActivity::class.java).apply {
                    action = ACTION_TOGGLE_FAVORITE
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
        context.getSharedPreferences("uclone_settings", Context.MODE_PRIVATE)
            .let { prefs ->
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
        const val EXTRA_PACKAGE_NAME = "com.uclone.restore.extra.PACKAGE_NAME"
        const val EXTRA_TOKEN = "com.uclone.restore.extra.LAUNCHER_TOKEN"
        private const val TOKEN_PREF_KEY = "launcherShortcutToken"
        private const val MAX_FAVORITE_SHORTCUTS = 4
        private const val SHORT_LABEL_MAX = 12
        private const val SHORTCUT_ICON_SIZE_PX = 192
    }
}
