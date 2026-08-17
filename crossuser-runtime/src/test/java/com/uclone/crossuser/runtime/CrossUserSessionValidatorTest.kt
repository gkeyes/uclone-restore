package com.uclone.crossuser.runtime

import com.uclone.crossuser.api.CrossUserProtocol
import com.uclone.crossuser.api.CrossUserSessionConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CrossUserSessionValidatorTest {
    @Test
    fun allowlistedWeChatTargetIsAccepted() {
        CrossUserSessionValidator.requireValid(
            validConfig().copy(
                packageName = CrossUserProtocol.WECHAT_PACKAGE,
                componentName = CrossUserProtocol.WECHAT_COMPONENT,
            ),
        )
    }

    @Test
    fun nonAllowlistedPackageIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            CrossUserSessionValidator.requireValid(validConfig().copy(packageName = "com.example.real"))
        }
    }

    @Test
    fun mismatchedAllowlistedComponentIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            CrossUserSessionValidator.requireValid(
                validConfig().copy(
                    packageName = CrossUserProtocol.WECHAT_PACKAGE,
                    componentName = CrossUserProtocol.PROBE_COMPONENT,
                ),
            )
        }
    }

    @Test
    fun ambiguousUserIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            CrossUserSessionValidator.requireValid(validConfig().copy(targetUserId = -2))
        }
    }

    @Test
    fun unrecognizedDisplayBackendIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            CrossUserSessionValidator.requireValid(validConfig().copy(backendId = "host_public_display"))
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
