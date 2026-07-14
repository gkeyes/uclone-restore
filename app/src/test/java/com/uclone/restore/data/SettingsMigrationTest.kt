package com.uclone.restore.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsMigrationTest {
    @Test
    fun newSettingWinsAndLegacyValueIsPreservedDuringMigration() {
        assertFalse(
            migratedCloneSyncSetting(
                hasCurrentValue = true,
                currentValue = false,
                hasLegacyValue = true,
                legacyValue = true,
            ),
        )
        assertFalse(
            migratedCloneSyncSetting(
                hasCurrentValue = false,
                currentValue = true,
                hasLegacyValue = true,
                legacyValue = false,
            ),
        )
        assertTrue(
            migratedCloneSyncSetting(
                hasCurrentValue = false,
                currentValue = false,
                hasLegacyValue = false,
                legacyValue = false,
            ),
        )
    }
}
