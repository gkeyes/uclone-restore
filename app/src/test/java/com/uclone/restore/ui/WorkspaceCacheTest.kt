package com.uclone.restore.ui

import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.sync.WorkspaceIndex
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkspaceCacheTest {
    private val settings = UCloneSettings()

    @Test
    fun cacheIsUsableOnlyForSameSettingsSuccessfulReadAndRevision() {
        val revision = 3L
        val cache = WorkspaceCache.from(settings, WorkspaceIndex(readSucceeded = true), revision)

        assertNotNull(cache.usableIndex(settings, revision))
        assertNull(cache.usableIndex(settings, revision + 1))
        assertNull(cache.usableIndex(settings.copy(rootDir = "/data/adb/other"), revision))
    }

    @Test
    fun failedWorkspaceReadIsNeverAConfirmedCacheEntry() {
        val cache = WorkspaceCache.from(settings, WorkspaceIndex(readSucceeded = false), 1_000L)

        assertNull(cache.usableIndex(settings, 1_000L))
    }
}
