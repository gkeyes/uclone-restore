package com.gkeyes.uclone.crossuserprobe

import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertTrue

class ProbeIdentityInstrumentedTest {
    @Test
    fun markerFilesCanBeWrittenInCeAndDeStorage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val cePath = ProbeIdentity.writeMarker(context, StorageKind.CE)
        val dePath = ProbeIdentity.writeMarker(context, StorageKind.DE)

        assertTrue(cePath.contains(context.packageName))
        assertTrue(dePath.contains(context.packageName))
        assertTrue(ProbeIdentity.readMarker(context, StorageKind.CE).startsWith("user"))
        assertTrue(ProbeIdentity.readMarker(context, StorageKind.DE).startsWith("user"))
    }
}
