package com.uclone.restore.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class PackageSigningIdentityProviderTest {
    @Test
    fun certificateSetDigestIsStableAndOrderIndependent() {
        val first = signingCertificateSetSha256(listOf("certificate-b".encodeToByteArray(), "certificate-a".encodeToByteArray()))
        val second = signingCertificateSetSha256(listOf("certificate-a".encodeToByteArray(), "certificate-b".encodeToByteArray()))

        assertEquals(first, second)
        assertEquals(2, first.split(',').size)
        first.split(',').forEach { assertEquals(64, it.length) }
    }
}
