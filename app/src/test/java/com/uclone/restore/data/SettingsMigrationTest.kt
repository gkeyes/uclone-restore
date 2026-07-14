package com.uclone.restore.data

import com.uclone.restore.model.CloneSessionPolicy
import com.uclone.restore.model.MainReturnPointPolicy
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

    @Test
    fun missingOrUnknownDataPoliciesUseSafeCompatibilityDefaults() {
        assertEquals(MainReturnPointPolicy.FIXED, migratedMainReturnPointPolicy(null))
        assertEquals(MainReturnPointPolicy.FIXED, migratedMainReturnPointPolicy("ALWAYS"))
        assertEquals(CloneSessionPolicy.SYNC_TO_CLONE_USER, migratedCloneSessionPolicy(null))
        assertEquals(CloneSessionPolicy.SYNC_TO_CLONE_USER, migratedCloneSessionPolicy("KEEP"))
    }

    @Test
    fun persistedDataPoliciesRoundTrip() {
        assertEquals(MainReturnPointPolicy.FIXED, migratedMainReturnPointPolicy("FIXED"))
        assertEquals(
            MainReturnPointPolicy.REFRESH_ON_MAIN_EXIT,
            migratedMainReturnPointPolicy("REFRESH_ON_MAIN_EXIT"),
        )
        assertEquals(
            CloneSessionPolicy.SYNC_TO_CLONE_USER,
            migratedCloneSessionPolicy("SYNC_TO_CLONE_USER"),
        )
        assertEquals(
            CloneSessionPolicy.DISCARD_ON_MAIN_RETURN,
            migratedCloneSessionPolicy("DISCARD_ON_MAIN_RETURN"),
        )
    }
}
