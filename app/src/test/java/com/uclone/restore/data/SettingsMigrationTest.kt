package com.uclone.restore.data

import com.uclone.restore.model.SwitchSafetyMode
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsMigrationTest {
    @Test
    fun missingOrUnknownModeMigratesToSafe() {
        assertEquals(SwitchSafetyMode.SAFE, migratedSwitchSafetyMode(null))
        assertEquals(SwitchSafetyMode.SAFE, migratedSwitchSafetyMode(""))
        assertEquals(SwitchSafetyMode.SAFE, migratedSwitchSafetyMode("FAST"))
    }

    @Test
    fun persistedModeRoundTrips() {
        assertEquals(SwitchSafetyMode.SAFE, migratedSwitchSafetyMode("SAFE"))
        assertEquals(SwitchSafetyMode.DANGEROUS_FAST, migratedSwitchSafetyMode("DANGEROUS_FAST"))
    }
}
