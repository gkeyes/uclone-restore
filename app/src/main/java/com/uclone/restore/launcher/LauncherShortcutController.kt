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

internal data class FavoriteShortcutSignature(
    val packageName: String,
    val label: String,
    val actionLabel: String,
    val iconVersion: Long = 0,
)

internal fun favoriteShortcutId(packageName: String): String = "favorite_toggle_$packageName"

internal fun shouldUpdateFavoriteShortcuts(
    previous: List<FavoriteShortcutSignature>?,
    current: List<FavoriteShortcutSignature>,
    actualShortcutIds: Set<String>?,
): Boolean {
    val expectedShortcutIds = current.mapTo(mutableSetOf()) { favoriteShortcutId(it.packageName) }
    if (current.isEmpty() && actualShortcutIds?.isEmpty() == true) return false
    return previous != current || actualShortcutIds == null || actualShortcutIds != expectedShortcutIds
}

class LauncherShortcutController(private val context: Context) {
    private var lastAppliedSignature: List<FavoriteShortcutSignature>? = null

    private val shortcutManager: ShortcutManager?
        get() = context.getSystemService(ShortcutManager::class.java)

    fun updateFavoriteShortcuts(entries: List<FavoriteShortcutEntry>) {
        val manager = shortcutManager ?: return
        val maxCount = manager.maxShortcutCountPerActivity.coerceAtMost(MAX_FAVORITE_SHORTCUTS)
        val visibleEntries = entries.take(maxCount.coerceAtLeast(0))
        val signature = visibleEntries.map { it.signature() }
        val actualShortcutIds = if (lastAppliedSignature == signature || signature.isEmpty()) {
            runCatching { manager.dynamicShortcuts.mapTo(mutableSetOf(), ShortcutInfo::getId) }.getOrNull()
        } else {
            null
        }
        if (!shouldUpdateFavoriteShortcuts(lastAppliedSignature, signature, actualShortcutIds)) return
        if (visibleEntries.isEmpty()) {
            manager.removeAllDynamicShortcuts()
            lastAppliedSignature = emptyList()
            return
        }
        val shortcuts = visibleEntries
            .mapIndexed { index, entry -> entry.toShortcut(index) }
        if (manager.setDynamicShortcuts(shortcuts)) {
            lastAppliedSignature = signature
        }
    }

    private fun FavoriteShortcutEntry.toShortcut(rank: Int): ShortcutInfo {
        val actionLabel = actionLabel()
        return ShortcutInfo.Builder(context, favoriteShortcutId(packageName))
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

    private fun FavoriteShortcutEntry.actionLabel(): String = when (dataState) {
        AppDataState.Main -> "切换"
        is AppDataState.Clone -> "还原"
        AppDataState.Unknown -> "检查状态"
    }

    private fun FavoriteShortcutEntry.signature(): FavoriteShortcutSignature =
        FavoriteShortcutSignature(packageName, label, actionLabel(), packageLastUpdateTime(packageName))

    @Suppress("DEPRECATION")
    private fun packageLastUpdateTime(packageName: String): Long =
        runCatching { context.packageManager.getPackageInfo(packageName, 0).lastUpdateTime }.getOrDefault(0L)

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
