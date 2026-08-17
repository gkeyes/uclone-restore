package com.uclone.crossuser.runtime

import com.uclone.crossuser.api.CrossUserProtocol
import com.uclone.crossuser.api.CrossUserSessionConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CrossUserSessionValidatorTest {
    @Test
    fun productionPackageIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            CrossUserSessionValidator.requireValid(validConfig().copy(packageName = "com.example.real"))
        }
    }

    @Test
    fun ambiguousUserIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            CrossUserSessionValidator.requireValid(validConfig().copy(targetUserId = -2))
        }
    }

    private fun validConfig() = CrossUserSessionConfig(
        packageName = CrossUserProtocol.PROBE_PACKAGE,
        componentName = CrossUserProtocol.PROBE_COMPONENT,
        mainUserId = 0,
        targetUserId = 10,
        widthPx = 1080,
        heightPx = 2400,
        densityDpi = 440,
        rotation = 0,
    )
}
