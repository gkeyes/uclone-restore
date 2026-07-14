package com.uclone.restore.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppIconPolicyTest {
    @Test
    fun iconIsVisibleOnlyForThePackageThatProducedIt() {
        assertTrue(shouldDisplayAppIcon("com.example.one", "com.example.one"))
        assertFalse(shouldDisplayAppIcon("com.example.two", "com.example.one"))
        assertFalse(shouldDisplayAppIcon("com.example.one", null))
    }
}
