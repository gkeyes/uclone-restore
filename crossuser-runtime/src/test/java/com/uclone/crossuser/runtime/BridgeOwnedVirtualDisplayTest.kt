package com.uclone.crossuser.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BridgeOwnedVirtualDisplayTest {
    @Test
    fun allRequiredTrustedDisplayFlagsAreCombined() {
        val values = CrossUserVirtualDisplayFlags.requiredHiddenFlags
            .mapIndexed { index, name -> name to (1 shl (index + 1)) }
            .toMap()

        val resolved = CrossUserVirtualDisplayFlags.resolve(baseFlags = 1) { name -> values.getValue(name) }

        assertEquals(values.values.fold(1) { flags, value -> flags or value }, resolved)
    }

    @Test
    fun targetFallbackContainsEveryRequiredFlag() {
        assertEquals(
            CrossUserVirtualDisplayFlags.requiredHiddenFlags.toSet(),
            CrossUserVirtualDisplayFlags.verifiedTargetValues.keys,
        )
        assertEquals(
            0x3e00,
            CrossUserVirtualDisplayFlags.resolve(0) { name ->
                CrossUserVirtualDisplayFlags.verifiedTargetValues.getValue(name)
            },
        )
    }

    @Test
    fun missingRequiredFlagFailsClosed() {
        assertFailsWith<NoSuchFieldException> {
            CrossUserVirtualDisplayFlags.resolve(baseFlags = 1) { name ->
                if (name == "VIRTUAL_DISPLAY_FLAG_TRUSTED") throw NoSuchFieldException(name)
                2
            }
        }
    }
}
