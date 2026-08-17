package com.uclone.crossuser.runtime

import android.view.KeyEvent
import android.view.MotionEvent
import com.uclone.crossuser.api.CrossUserKeyPacket
import com.uclone.crossuser.api.CrossUserTouchPacket
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CrossUserInputValidationTest {
    @Test
    fun touchOutsideTargetDisplayIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            CrossUserInputValidation.requireTouch(
                CrossUserTouchPacket(
                    action = MotionEvent.ACTION_DOWN,
                    x = 1081f,
                    y = 1f,
                    downTimeMillis = 1L,
                    eventTimeMillis = 1L,
                ),
                displayId = 42,
                widthPx = 1080,
                heightPx = 2400,
            )
        }
    }

    @Test
    fun globalNavigationKeyIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            CrossUserInputValidation.requireKey(
                CrossUserKeyPacket(
                    action = KeyEvent.ACTION_DOWN,
                    keyCode = KeyEvent.KEYCODE_HOME,
                    downTimeMillis = 1L,
                    eventTimeMillis = 1L,
                ),
                displayId = 42,
            )
        }
    }
}
