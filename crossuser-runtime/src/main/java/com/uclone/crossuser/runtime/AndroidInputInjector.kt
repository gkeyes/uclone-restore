package com.uclone.crossuser.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import com.uclone.crossuser.api.CrossUserKeyPacket
import com.uclone.crossuser.api.CrossUserProtocol
import com.uclone.crossuser.api.CrossUserTouchPacket

internal interface CrossUserInputInjector {
    fun injectTouch(packet: CrossUserTouchPacket, displayId: Int, widthPx: Int, heightPx: Int): Boolean
    fun injectKey(packet: CrossUserKeyPacket, displayId: Int): Boolean
}

internal class AndroidInputInjector(context: Context) : CrossUserInputInjector {
    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager

    override fun injectTouch(
        packet: CrossUserTouchPacket,
        displayId: Int,
        widthPx: Int,
        heightPx: Int,
    ): Boolean {
        CrossUserInputValidation.requireTouch(packet, displayId, widthPx, heightPx)
        val event = MotionEvent.obtain(
            packet.downTimeMillis,
            packet.eventTimeMillis,
            packet.action,
            packet.x,
            packet.y,
            1f,
            1f,
            0,
            1f,
            1f,
            0,
            0,
        )
        return event.useInjected(displayId)
    }

    override fun injectKey(packet: CrossUserKeyPacket, displayId: Int): Boolean {
        CrossUserInputValidation.requireKey(packet, displayId)
        val event = KeyEvent(
            packet.downTimeMillis,
            packet.eventTimeMillis,
            packet.action,
            packet.keyCode,
            0,
            0,
            KeyEvent.KEYCODE_UNKNOWN,
            0,
            KeyEvent.FLAG_FROM_SYSTEM,
            InputDevice.SOURCE_KEYBOARD,
        )
        return event.useInjected(displayId)
    }

    @SuppressLint("BlockedPrivateApi")
    private fun InputEvent.useInjected(displayId: Int): Boolean = try {
        if (this is MotionEvent) source = InputDevice.SOURCE_TOUCHSCREEN
        InputEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType).apply {
            isAccessible = true
        }.invoke(this, displayId)
        val inject = InputManager::class.java.getDeclaredMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        inject.invoke(inputManager, this, INJECT_WAIT_FOR_RESULT) as? Boolean ?: false
    } catch (_: ReflectiveOperationException) {
        false
    } finally {
        if (this is MotionEvent) recycle()
    }

    private companion object {
        const val INJECT_WAIT_FOR_RESULT = 1
    }
}

internal object CrossUserInputValidation {
    fun requireTouch(packet: CrossUserTouchPacket, displayId: Int, widthPx: Int, heightPx: Int) {
        require(packet.schemaVersion == CrossUserProtocol.SCHEMA_VERSION)
        require(displayId > 0)
        require(packet.action in setOf(
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
        ))
        require(packet.x.isFinite() && packet.x in 0f..widthPx.toFloat())
        require(packet.y.isFinite() && packet.y in 0f..heightPx.toFloat())
        require(packet.downTimeMillis > 0L)
        require(packet.eventTimeMillis >= packet.downTimeMillis)
    }

    fun requireKey(packet: CrossUserKeyPacket, displayId: Int) {
        require(packet.schemaVersion == CrossUserProtocol.SCHEMA_VERSION)
        require(displayId > 0)
        require(packet.keyCode == KeyEvent.KEYCODE_BACK) { "preview only permits Back" }
        require(packet.action == KeyEvent.ACTION_DOWN || packet.action == KeyEvent.ACTION_UP)
        require(packet.downTimeMillis > 0L)
        require(packet.eventTimeMillis >= packet.downTimeMillis)
    }
}
