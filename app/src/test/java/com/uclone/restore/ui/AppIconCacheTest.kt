package com.uclone.restore.ui

import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class AppIconCacheTest {
    @Test
    fun missingIconIsRetriedInsteadOfBeingCachedForever() = runBlocking {
        val packageName = "missing.${UUID.randomUUID()}"
        var loads = 0

        repeat(2) {
            ApplicationIconCache.getOrLoad(packageName) {
                loads += 1
                CachedAppIcon.Missing
            }
        }

        assertEquals(2, loads)
    }
}
