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

data class FavoriteShortcutEntry(
    val packageName: String,
    val label: String,
    val switched: Boolean,
)

data class LauncherShortcutRequest(
    val packageName: String,
    val nonce: Long = SystemClock.uptimeMillis(),
) {
    companion object {
        fun fromIntent(intent: Intent?): LauncherShortcutRequest? {
            if (intent?.action != LauncherShortcutController.ACTION_TOGGLE_FAVORITE) return null
            val packageName = intent.getStringExtra(LauncherShortcutController.EXTRA_PACKAGE_NAME)
                ?.takeIf(String::isNotBlank)
                ?: return null
            return LauncherShortcutRequest(packageName)
        }
    }
}

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
        val actionLabel = if (switched) "还原" else "切换"
        return ShortcutInfo.Builder(context, shortcutId(packageName))
            .setShortLabel(compactLabel("$actionLabel $label"))
            .setLongLabel("$actionLabel $label")
            .setIcon(shortcutIcon())
            .setIntent(
                Intent(context, MainActivity::class.java).apply {
                    action = ACTION_TOGGLE_FAVORITE
                    putExtra(EXTRA_PACKAGE_NAME, packageName)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
            )
            .setRank(rank)
            .build()
    }

    private fun compactLabel(value: String): String =
        if (value.length <= SHORT_LABEL_MAX) value else value.take(SHORT_LABEL_MAX - 1) + "…"

    private fun shortcutId(packageName: String): String = "favorite_toggle_$packageName"

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
        private const val MAX_FAVORITE_SHORTCUTS = 4
        private const val SHORT_LABEL_MAX = 12
        private const val SHORTCUT_ICON_SIZE_PX = 192
    }
}
