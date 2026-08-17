package com.uclone.restore.crossuser.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.uclone.crossuser.api.CrossUserCapabilityReport
import com.uclone.crossuser.api.CrossUserKeyPacket
import com.uclone.crossuser.api.CrossUserProbeRequest
import com.uclone.crossuser.api.CrossUserProtocol
import com.uclone.crossuser.api.CrossUserSessionConfig
import com.uclone.crossuser.api.CrossUserSessionState
import com.uclone.crossuser.api.CrossUserTouchPacket
import com.uclone.crossuser.runtime.CrossUserRuntimeCallback
import com.uclone.crossuser.runtime.CrossUserRuntimeErrors
import com.uclone.restore.UCloneApplication
import com.uclone.restore.crossuser.session.CrossUserSessionCoordinator
import com.uclone.restore.ui.UCloneTheme
import kotlinx.coroutines.launch

class CrossUserHostActivity : ComponentActivity() {
    private lateinit var coordinator: CrossUserSessionCoordinator
    private var uiState by mutableStateOf(CrossUserHostUiState())
    private var sessionId: String? = null
    private var opening = false
    private var latestSurface: Surface? = null
    private val openAttemptGate = CrossUserOpenAttemptGate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as UCloneApplication).container
        if (!container.settingsStore.load().crossUserPreviewEnabled) {
            Toast.makeText(this, "跨用户真实运行预览尚未开启", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        coordinator = container.crossUserSessionCoordinator
        setContent {
            UCloneTheme {
                CrossUserHostScreen(
                    state = uiState,
                    onClose = { closeAndFinish(CLOSE_REASON_USER) },
                    onRetry = {
                        latestSurface?.let {
                            openOrReplace(
                                it,
                                uiState.surfaceWidth,
                                uiState.surfaceHeight,
                                manualRetry = true,
                            )
                        }
                    },
                    onBack = ::injectBack,
                    onSurfaceChanged = { surface, width, height ->
                        openOrReplace(surface, width, height)
                    },
                    onSurfaceDestroyed = ::onSurfaceDestroyed,
                    onTouch = ::onTouch,
                )
            }
        }
    }

    override fun onDestroy() {
        val id = sessionId
        if (id != null) {
            lifecycleScope.launch { coordinator.close(id, CLOSE_REASON_HOST_DESTROYED) }
        }
        super.onDestroy()
    }

    private fun openOrReplace(
        surface: Surface,
        width: Int,
        height: Int,
        manualRetry: Boolean = false,
    ) {
        latestSurface = surface
        uiState = uiState.copy(surfaceWidth = width, surfaceHeight = height)
        if (!surface.isValid || opening) return
        if (width < MIN_DISPLAY_EDGE || height < MIN_DISPLAY_EDGE) return
        val existing = sessionId
        if (existing != null) {
            lifecycleScope.launch {
                runCatching {
                    coordinator.replaceSurface(
                        existing,
                        surface,
                        width,
                        height,
                        resources.displayMetrics.densityDpi,
                        rotation = 0,
                    )
                }.onFailure(::showFailure)
            }
            return
        }
        if (!openAttemptGate.allow(manualRetry)) return
        opening = true
        uiState = uiState.copy(stage = "正在检查设备能力", errorCode = null, detail = "")
        lifecycleScope.launch {
            runCatching {
                val report = coordinator.probe(
                    CrossUserProbeRequest(mainUserId = 0, targetUserId = 10),
                )
                val decision = previewDecision(report)
                uiState = uiState.copy(
                    adapterId = report.adapterId,
                    detail = decision.message,
                )
                check(decision.canOpen) { decision.errorCode ?: CrossUserRuntimeErrors.FEATURE_VISIBLE_USERS_DISABLED }
                coordinator.open(
                    config = weChatSessionConfig(width, height, resources.displayMetrics.densityDpi),
                    surface = surface,
                    callback = runtimeCallback,
                )
            }.onSuccess { state ->
                sessionId = state.sessionId
                updateState(state)
            }.onFailure { error ->
                openAttemptGate.requireManualRetry()
                showFailure(error)
            }
            opening = false
        }
    }

    private fun onSurfaceDestroyed() {
        latestSurface = null
        val id = sessionId ?: return
        sessionId = null
        lifecycleScope.launch {
            val result = coordinator.close(id, CLOSE_REASON_SURFACE_LOST)
            result?.let(::updateState)
        }
    }

    private fun onTouch(event: MotionEvent): Boolean {
        val id = sessionId ?: return false
        if (event.pointerCount != 1 || event.actionMasked !in SUPPORTED_TOUCH_ACTIONS) return false
        val packet = CrossUserTouchPacket(
            action = event.actionMasked,
            x = event.x.coerceIn(0f, uiState.surfaceWidth.toFloat()),
            y = event.y.coerceIn(0f, uiState.surfaceHeight.toFloat()),
            downTimeMillis = event.downTime,
            eventTimeMillis = event.eventTime,
        )
        lifecycleScope.launch {
            if (!coordinator.injectTouch(id, packet)) {
                uiState = uiState.copy(errorCode = CrossUserRuntimeErrors.INPUT_PERMISSION_DENIED)
            }
        }
        return true
    }

    private fun injectBack() {
        val id = sessionId ?: run {
            finish()
            return
        }
        val downTime = SystemClock.uptimeMillis()
        lifecycleScope.launch {
            val down = coordinator.injectKey(
                id,
                CrossUserKeyPacket(
                    action = KeyEvent.ACTION_DOWN,
                    keyCode = KeyEvent.KEYCODE_BACK,
                    downTimeMillis = downTime,
                    eventTimeMillis = downTime,
                ),
            )
            val upTime = SystemClock.uptimeMillis().coerceAtLeast(downTime)
            val up = coordinator.injectKey(
                id,
                CrossUserKeyPacket(
                    action = KeyEvent.ACTION_UP,
                    keyCode = KeyEvent.KEYCODE_BACK,
                    downTimeMillis = downTime,
                    eventTimeMillis = upTime,
                ),
            )
            if (!down || !up) uiState = uiState.copy(errorCode = CrossUserRuntimeErrors.INPUT_PERMISSION_DENIED)
        }
    }

    private fun closeAndFinish(reason: Int) {
        val id = sessionId
        sessionId = null
        if (id == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            coordinator.close(id, reason)
            finish()
        }
    }

    private fun showFailure(error: Throwable) {
        uiState = uiState.copy(
            stage = "预览不可用",
            errorCode = error.message ?: error::class.java.simpleName,
        )
    }

    private fun updateState(state: CrossUserSessionState) {
        uiState = uiState.copy(
            stage = state.state,
            displayId = state.displayId,
            sessionId = state.sessionId,
            errorCode = state.errorCode,
            detail = state.message,
        )
    }

    private val runtimeCallback = object : CrossUserRuntimeCallback {
        override fun onStateChanged(state: CrossUserSessionState) {
            runOnUiThread { updateState(state) }
        }

        override fun onRecoverableError(sessionId: String, code: String, message: String) {
            runOnUiThread { uiState = uiState.copy(errorCode = code, detail = message) }
        }

        override fun onTerminal(sessionId: String, code: String, message: String) {
            runOnUiThread { uiState = uiState.copy(errorCode = code.takeUnless { it == "CLOSED" }, detail = message) }
        }
    }

    private companion object {
        const val MIN_DISPLAY_EDGE = 320
        const val CLOSE_REASON_USER = 1
        const val CLOSE_REASON_HOST_DESTROYED = 2
        const val CLOSE_REASON_SURFACE_LOST = 3
        val SUPPORTED_TOUCH_ACTIONS = setOf(
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
        )
    }
}

internal data class CrossUserHostUiState(
    val stage: String = "等待显示 Surface",
    val detail: String = "仅允许显示 user10 的真实微信；不会复制或替换 App 数据。",
    val adapterId: String = "未探测",
    val displayId: Int = -1,
    val sessionId: String = "",
    val errorCode: String? = null,
    val surfaceWidth: Int = 0,
    val surfaceHeight: Int = 0,
)

internal data class CrossUserPreviewDecision(
    val canOpen: Boolean,
    val errorCode: String?,
    val message: String,
)

internal class CrossUserOpenAttemptGate {
    private var manualRetryRequired = false

    fun allow(manualRetry: Boolean): Boolean {
        if (manualRetry) manualRetryRequired = false
        return !manualRetryRequired
    }

    fun requireManualRetry() {
        manualRetryRequired = true
    }
}

internal fun previewDecision(report: CrossUserCapabilityReport): CrossUserPreviewDecision {
    val capability = report.capabilities.firstOrNull {
        it.name == CrossUserProtocol.CAPABILITY_VISIBLE_BACKGROUND_USERS_RUNTIME
    }
    val supported = capability?.status == CrossUserProtocol.STATUS_SUPPORTED
    return CrossUserPreviewDecision(
        canOpen = supported && report.rootUid == 0 && report.selinuxEnforcing,
        errorCode = when {
            report.rootUid != 0 -> "ROOT_UID_REQUIRED"
            !report.selinuxEnforcing -> "SELINUX_ENFORCING_REQUIRED"
            !supported -> CrossUserRuntimeErrors.FEATURE_VISIBLE_USERS_DISABLED
            else -> null
        },
        message = capability?.humanMessage ?: "设备未返回可见后台用户能力",
    )
}

internal fun weChatSessionConfig(widthPx: Int, heightPx: Int, densityDpi: Int) =
    CrossUserSessionConfig(
        packageName = CrossUserProtocol.WECHAT_PACKAGE,
        componentName = CrossUserProtocol.WECHAT_COMPONENT,
        mainUserId = 0,
        targetUserId = 10,
        widthPx = widthPx,
        heightPx = heightPx,
        densityDpi = densityDpi,
        rotation = 0,
    )

@Composable
private fun CrossUserHostScreen(
    state: CrossUserHostUiState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onSurfaceChanged: (Surface, Int, Int) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onTouch: (MotionEvent) -> Boolean,
) {
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        MaterialSurface(tonalElevation = 2.dp) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("微信 · user10", style = MaterialTheme.typography.titleMedium)
                        Text(state.stage, style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(onClick = onClose) { Text("关闭") }
                }
                Text(
                    "display=${state.displayId} · adapter=${state.adapterId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    state.errorCode?.let { "$it · ${state.detail}" } ?: state.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.errorCode == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if (state.errorCode != null) Button(onClick = onRetry) { Text("重新检查") }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    SurfaceView(context).apply {
                        setOnTouchListener { _, event -> onTouch(event) }
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = Unit

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int,
                            ) {
                                onSurfaceChanged(holder.surface, width, height)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) = onSurfaceDestroyed()
                        })
                    }
                },
            )
        }
    }
}
