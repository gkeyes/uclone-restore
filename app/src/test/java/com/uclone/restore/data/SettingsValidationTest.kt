package com.uclone.restore.data

import com.uclone.restore.model.UCloneSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SettingsValidationTest {
    @Test
    fun defaultSettingsAreValid() {
        assertNull(SettingsValidation.error(UCloneSettings()))
    }

    @Test
    fun negativeUserIdsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            SettingsValidation.requireValid(UCloneSettings(mainUserId = -1))
        }
        assertFailsWith<IllegalArgumentException> {
            SettingsValidation.requireValid(UCloneSettings(cloneUserId = -1))
        }
    }

    @Test
    fun identicalUserIdsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            SettingsValidation.requireValid(UCloneSettings(mainUserId = 10, cloneUserId = 10))
        }
    }

    @Test
    fun unsafeRootDirectoriesAreRejected() {
        listOf("", "   ", "/", "relative/path").forEach { rootDir ->
            assertFailsWith<IllegalArgumentException>(rootDir) {
                SettingsValidation.requireValid(UCloneSettings(rootDir = rootDir))
            }
        }
    }

    @Test
    fun rootDirectoryWithInternalWhitespaceIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            SettingsValidation.requireValid(UCloneSettings(rootDir = "/data/adb/u clone"))
        }
    }

    @Test
    fun rootDirectoryWithBackslashIsRejectedBecauseMountInfoEscapesIt() {
        assertFailsWith<IllegalArgumentException> {
            SettingsValidation.requireValid(UCloneSettings(rootDir = "/data/adb/uclone\\archive"))
        }
    }

    @Test
    fun settingsAreNormalizedBeforePersistence() {
        val normalized = SettingsValidation.normalized(
            UCloneSettings(rootDir = "  /data/adb/uclone///  "),
        )

        assertEquals("/data/adb/uclone", normalized.rootDir)
    }

    @Test
    fun invalidLegacyValuesFallBackWithoutDiscardingOtherPreferences() {
        val sanitized = SettingsValidation.sanitizedForLoad(
            UCloneSettings(
                mainUserId = -1,
                cloneUserId = -1,
                rootDir = "/",
                includeMedia = true,
                forceUpdateCloneDataBeforeMainRestore = true,
            ),
        )

        assertEquals(0, sanitized.mainUserId)
        assertEquals(10, sanitized.cloneUserId)
        assertEquals("/data/adb/uclone", sanitized.rootDir)
        assertEquals(true, sanitized.includeMedia)
        assertEquals(true, sanitized.forceUpdateCloneDataBeforeMainRestore)
    }
}
