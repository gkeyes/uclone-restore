package com.uclone.restore.ui

import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface CachedAppIcon {
    data class Loaded(val bitmap: ImageBitmap) : CachedAppIcon
    data object Missing : CachedAppIcon
}

internal object ApplicationIconCache {
    private const val MAX_ENTRIES = 64
    private val entries = BoundedLruCache<String, CachedAppIcon>(MAX_ENTRIES)

    operator fun get(packageName: String): CachedAppIcon? = entries[packageName]

    suspend fun getOrLoad(packageName: String, loader: () -> CachedAppIcon): CachedAppIcon {
        entries[packageName]?.let { return it }
        return withContext(Dispatchers.IO) {
            entries[packageName] ?: loader().also { loaded ->
                if (loaded is CachedAppIcon.Loaded) entries.put(packageName, loaded)
            }
        }
    }
}

internal suspend fun loadApplicationIcon(
    packageManager: PackageManager,
    packageName: String,
): CachedAppIcon {
    return ApplicationIconCache.getOrLoad(packageName) {
        runCatching {
            packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
        }.fold(
            onSuccess = CachedAppIcon::Loaded,
            onFailure = { CachedAppIcon.Missing },
        )
    }
}
