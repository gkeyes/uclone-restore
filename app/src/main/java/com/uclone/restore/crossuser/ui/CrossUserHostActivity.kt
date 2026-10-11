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
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.uclone.restore.ui.CompactActionButton
import com.uclone.restore.ui.StatusTone
import com.uclone.restore.ui.ToneChip
import com.uclone.restore.ui.UCloneRadius
import com.uclone.restore.ui.UCloneSpacing
import com.uclone.restore.ui.ucloneColors
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
import com.uclone.restore.crossuser.user.CrossUserCloneCredentialUnlocker
import com.uclone.restore.crossuser.user.CrossUserCloneUnlockException
import com.uclone.restore.data.SettingsStore
import com.uclone.restore.ui.UCloneTheme
import kotlinx.coroutines.launch

class CrossUserHostActivity : ComponentActivity() {
    private lateinit var coordinator: CrossUserSessionCoordinator
    private lateinit var settingsStore: SettingsStore
    private lateinit var cloneCredentialUnlocker: CrossUserCloneCredentialUnlocker
    private var uiState by mutableStateOf(CrossUserHostUiState())
    private var sessionId: String? = null
    private var opening = false
    private var pendingSurfaceChange = false
    private var latestSurface: Surface? = null
    private val openAttemptGate = CrossUserOpenAttemptGate()
    private val surfaceAttemptTracker = CrossUserSurfaceAttemptTracker()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as UCloneApplication).container
        settingsStore = container.settingsStore
        cloneCredentialUnlocker = container.crossUserCloneCredentialUnlocker
        if (!settingsStore.load().crossUserPreviewEnabled) {
            Toast.makeText(this, "跨用户真实运行预览尚未开启", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        coordinator = container.crossUserSessionCoordinator
        enableEdgeToEdge()
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
        surfaceAttemptTracker.surfaceDestroyed()
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
        val surfaceAttempt = surfaceAttemptTracker.nextSurface()
        uiState = uiState.copy(surfaceWidth = width, surfaceHeight = height)
        if (!surface.isValid) return
        if (width < MIN_DISPLAY_EDGE || height < MIN_DISPLAY_EDGE) return
        if (opening) {
            pendingSurfaceChange = true
            return
        }
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
        pendingSurfaceChange = false
        uiState = uiState.copy(stage = "正在检查设备能力", errorCode = null, detail = "")
        lifecycleScope.launch {
            var openedSessionId: String? = null
            try {
                val report = coordinator.probe(
                    CrossUserProbeRequest(mainUserId = 0, targetUserId = 10),
                )
                val decision = previewDecision(report)
                uiState = uiState.copy(
                    adapterId = report.adapterId,
                    detail = decision.message,
                )
                check(decision.canOpen) { decision.errorCode ?: CrossUserRuntimeErrors.FEATURE_VISIBLE_USERS_DISABLED }
                if (!surfaceAttemptTracker.isCurrent(surfaceAttempt) || !surface.isValid) return@launch
                val displayReady = coordinator.open(
                    config = weChatSessionConfig(width, height, resources.displayMetrics.densityDpi),
                    surface = surface,
                    callback = runtimeCallback,
                )
                openedSessionId = displayReady.sessionId
                sessionId = displayReady.sessionId
                updateState(displayReady)
                if (!keepSessionIfSurfaceCurrent(displayReady.sessionId, surfaceAttempt)) return@launch

                uiState = uiState.copy(stage = "正在把 user10 映射到手机内嵌显示", errorCode = null)
                val visibleState = coordinator.startVisibleUser(displayReady.sessionId)
                updateState(visibleState)
                if (!keepSessionIfSurfaceCurrent(displayReady.sessionId, surfaceAttempt)) return@launch

                when (visibleState.state) {
                    CrossUserProtocol.SESSION_USER_VISIBLE_UNLOCKED -> Unit
                    CrossUserProtocol.SESSION_USER_VISIBLE_LOCKED -> {
                        val settings = settingsStore.load()
                        check(settings.cloneUserId == 10) { CrossUserRuntimeErrors.TARGET_PACKAGE_DENIED }
                        uiState = uiState.copy(
                            stage = "正在使用已保存密码自动解锁 user10",
                            errorCode = null,
                            detail = "密码仅通过受保护的标准输入发送，不写入会话或日志。",
                        )
                        cloneCredentialUnlocker.unlockStartedUser(
                            userId = settings.cloneUserId,
                            credential = settings.cloneUnlockCredential,
                        )
                        if (!keepSessionIfSurfaceCurrent(displayReady.sessionId, surfaceAttempt)) return@launch
                        uiState = uiState.copy(stage = CrossUserProtocol.SESSION_USER_VISIBLE_UNLOCKED)
                    }
                    else -> error(CrossUserRuntimeErrors.DISPLAY_ASSIGNMENT_FAILED)
                }

                uiState = uiState.copy(stage = "正在启动 user10 微信", errorCode = null)
                val launched = coordinator.launchTarget(displayReady.sessionId)
                if (!keepSessionIfSurfaceCurrent(displayReady.sessionId, surfaceAttempt)) return@launch
                updateState(launched)
            } catch (error: Throwable) {
                openedSessionId?.let { id ->
                    if (sessionId == id) sessionId = null
                    runCatching { coordinator.close(id, CLOSE_REASON_START_FAILED) }
                }
                if (surfaceAttemptTracker.isCurrent(surfaceAttempt) && latestSurface?.isValid == true) {
                    openAttemptGate.requireManualRetry()
                    showFailure(error)
                }
            } finally {
                opening = false
                replayPendingSurfaceChange()
            }
        }
    }

    private fun onSurfaceDestroyed() {
        surfaceAttemptTracker.surfaceDestroyed()
        latestSurface = null
        pendingSurfaceChange = false
        val id = sessionId ?: return
        sessionId = null
        lifecycleScope.launch {
            val result = coordinator.close(id, CLOSE_REASON_SURFACE_LOST)
            result?.let(::updateState)
        }
    }

    private suspend fun keepSessionIfSurfaceCurrent(id: String, surfaceAttempt: Long): Boolean {
        val current = surfaceAttemptTracker.isCurrent(surfaceAttempt) &&
            latestSurface?.isValid == true &&
            sessionId == id &&
            !isFinishing
        if (current) return true
        if (sessionId == id) sessionId = null
        runCatching { coordinator.close(id, CLOSE_REASON_SURFACE_LOST) }
        return false
    }

    private fun replayPendingSurfaceChange() {
        if (!pendingSurfaceChange || isFinishing) return
        pendingSurfaceChange = false
        val surface = latestSurface?.takeIf { it.isValid } ?: return
        openOrReplace(surface, uiState.surfaceWidth, uiState.surfaceHeight)
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
        surfaceAttemptTracker.surfaceDestroyed()
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
        val code = (error as? CrossUserCloneUnlockException)?.errorCode
            ?: error.message
            ?: error::class.java.simpleName
        uiState = uiState.copy(
            stage = "预览不可用",
            errorCode = code,
            detail = error.message.orEmpty(),
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
        const val CLOSE_REASON_START_FAILED = 4
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

internal class CrossUserSurfaceAttemptTracker {
    private var generation = 0L

    @Synchronized
    fun nextSurface(): Long {
        generation += 1
        return generation
    }

    @Synchronized
    fun surfaceDestroyed() {
        generation += 1
    }

    @Synchronized
    fun isCurrent(attempt: Long): Boolean = attempt == generation
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
        MaterialSurface(color = MaterialTheme.ucloneColors.groupedSurface) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = UCloneSpacing.md, vertical = UCloneSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(UCloneSpacing.xs),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(UCloneSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UCloneSpacing.xxs)) {
                        Text("微信 · user10", style = MaterialTheme.typography.titleMedium)
                        ToneChip(
                            label = state.stage,
                            tone = if (state.errorCode == null) StatusTone.INFO else StatusTone.DANGER,
                            showDot = true,
                        )
                    }
                    CompactActionButton(text = "关闭", onClick = onClose)
                }
                Text(
                    "display=${state.displayId} · adapter=${state.adapterId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val danger = MaterialTheme.ucloneColors.dangerTone
                Text(
                    state.errorCode?.let { "$it · ${state.detail}" } ?: state.detail,
                    modifier = if (state.errorCode == null) {
                        Modifier
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .background(danger.container, RoundedCornerShape(UCloneRadius.control))
                            .padding(horizontal = UCloneSpacing.sm, vertical = UCloneSpacing.xs)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.errorCode == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        danger.content
                    },
                )
                if (state.errorCode != null) {
                    CompactActionButton(text = "重新检查", onClick = onRetry, primary = true)
                }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .navigationBarsPadding(),
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
