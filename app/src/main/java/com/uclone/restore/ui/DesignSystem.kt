package com.uclone.restore.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* =============================================================================================
 * Motion tokens (DESIGN_SYSTEM.md §1 動效). Every spec collapses to snap() when the system
 * animator scale is 0; no logic waits for an animation to finish.
 * ============================================================================================= */

object UCloneMotion {
    /** Position / size. */
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.86f, stiffness = 380f)

    /** Color / alpha. */
    fun <T> effects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 600f)

    fun <T> pressIn(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1500f)
    fun <T> pressRelease(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.62f, stiffness = 420f)
    fun <T> page(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 380f)
    fun <T> sheetEnter(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 380f)
    fun <T> dialogEnter(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 500f)

    val exitEasing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    fun <T> exit(): FiniteAnimationSpec<T> = tween(durationMillis = 220, easing = exitEasing)
    fun <T> scrim(): FiniteAnimationSpec<T> = tween(durationMillis = 220)

    const val PressedScale = 0.965f
    const val PressedScaleFullWidthRow = 0.985f
    const val StaggerStepMillis = 26L
    const val StaggerMaxBlocks = 8
    val staggerOffset = 16.dp
    val pageOffset = 30.dp
}

@Composable
internal fun <T> motionSpec(spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
    if (rememberReduceMotionEnabled()) snap() else spec

/* =============================================================================================
 * Haptics (DESIGN_SYSTEM.md §1 觸覺)
 * ============================================================================================= */

internal enum class UCloneHaptic { ToggleOn, ToggleOff, SegmentTick, Confirm, Reject, LongPress }

@Composable
internal fun rememberHaptics(): (UCloneHaptic) -> Unit {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    return remember(haptics) {
        { kind ->
            haptics.performHapticFeedback(
                when (kind) {
                    UCloneHaptic.ToggleOn -> HapticFeedbackType.ToggleOn
                    UCloneHaptic.ToggleOff -> HapticFeedbackType.ToggleOff
                    UCloneHaptic.SegmentTick -> HapticFeedbackType.SegmentTick
                    UCloneHaptic.Confirm -> HapticFeedbackType.Confirm
                    UCloneHaptic.Reject -> HapticFeedbackType.Reject
                    UCloneHaptic.LongPress -> HapticFeedbackType.LongPress
                },
            )
        }
    }
}

/* =============================================================================================
 * Press feedback: scale to 0.965 (full-width rows 0.985) with a stiff spring, release with a
 * light bounce. Values are read in the graphics layer only.
 * ============================================================================================= */

internal fun Modifier.pressFeedback(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    fullWidthRow: Boolean = false,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduceMotion = rememberReduceMotionEnabled()
    val target = if (pressed && enabled && !reduceMotion) {
        if (fullWidthRow) UCloneMotion.PressedScaleFullWidthRow else UCloneMotion.PressedScale
    } else {
        1f
    }
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = when {
            reduceMotion -> snap()
            pressed -> UCloneMotion.pressIn()
            else -> UCloneMotion.pressRelease()
        },
        label = "pressFeedback",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/* =============================================================================================
 * Staggered entrance: the first 8 blocks of a screen rise 16dp and fade in, 26ms apart, once per
 * screen per process.
 * ============================================================================================= */

internal class EnterStagger(var armed: Boolean) {
    private var counter = 0
    fun next(): Int = counter++
}

private object StaggerRegistry {
    val played = mutableSetOf<String>()
}

internal val LocalEnterStagger = staticCompositionLocalOf<EnterStagger?> { null }

@Composable
internal fun rememberEnterStagger(screenKey: String): EnterStagger {
    val reduceMotion = rememberReduceMotionEnabled()
    val stagger = remember(screenKey) {
        EnterStagger(armed = !reduceMotion && screenKey !in StaggerRegistry.played)
    }
    LaunchedEffect(stagger) {
        withFrameNanos { }
        stagger.armed = false
        StaggerRegistry.played += screenKey
    }
    return stagger
}

internal fun Modifier.staggerEnter(): Modifier = composed {
    val stagger = LocalEnterStagger.current
    val order = remember { if (stagger?.armed == true) stagger.next() else Int.MAX_VALUE }
    val animate = order < UCloneMotion.StaggerMaxBlocks
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    if (animate) {
        LaunchedEffect(Unit) {
            delay(order * UCloneMotion.StaggerStepMillis)
            progress.animateTo(1f, UCloneMotion.spatial())
        }
    }
    val offsetPx = with(LocalDensity.current) { UCloneMotion.staggerOffset.toPx() }
    graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * offsetPx
    }
}

/* =============================================================================================
 * Collapsing large title: screens report whether their large title has scrolled away; the shell
 * shows a 17sp compact title with a fading hairline.
 * ============================================================================================= */

internal val LocalTitleCollapsed = staticCompositionLocalOf<MutableState<Boolean>?> { null }
private val CollapseThreshold = 56.dp

@Composable
internal fun rememberCollapsingScrollState(): ScrollState {
    val state = rememberScrollState()
    val collapsed = LocalTitleCollapsed.current
    val thresholdPx = with(LocalDensity.current) { CollapseThreshold.toPx() }
    LaunchedEffect(state, collapsed) {
        if (collapsed != null) snapshotFlow { state.value > thresholdPx }.collect { collapsed.value = it }
    }
    return state
}

@Composable
internal fun rememberCollapsingLazyListState(): LazyListState {
    val state = rememberLazyListState()
    ReportTitleCollapse(state)
    return state
}

@Composable
internal fun ReportTitleCollapse(state: LazyListState) {
    val collapsed = LocalTitleCollapsed.current
    val thresholdPx = with(LocalDensity.current) { CollapseThreshold.toPx() }
    LaunchedEffect(state, collapsed) {
        if (collapsed != null) {
            snapshotFlow { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > thresholdPx }
                .collect { collapsed.value = it }
        }
    }
}

/** Compact title bar shown by the shell over top-level screens once the large title is gone. */
@Composable
internal fun CompactTitleBar(title: String, collapsed: Boolean, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(
        targetValue = if (collapsed) 1f else 0f,
        animationSpec = motionSpec(UCloneMotion.effects()),
        label = "compactTitle",
    )
    if (alpha <= 0f) return
    val page = MaterialTheme.ucloneColors.page
    val hairline = MaterialTheme.ucloneColors.separator
    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .background(page.copy(alpha = 0.94f))
            .drawBehind {
                drawLine(hairline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
            }
            .statusBarsPadding()
            .height(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            style = CollapsedTitleStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = 64.dp)
                .semantics { heading() },
        )
    }
}

/** Large title for secondary screens; it collapses into the floating top bar title. */
@Composable
internal fun ScreenLargeTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .staggerEnter()
            .padding(horizontal = UCloneSpacing.xxs, vertical = UCloneSpacing.xxs),
        verticalArrangement = Arrangement.spacedBy(UCloneSpacing.xxs),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/* =============================================================================================
 * Page transition: shared X axis, 30dp + fade, reversed when going back.
 * ============================================================================================= */

internal fun sharedAxisTransition(forward: Boolean, offsetPx: Int, reduceMotion: Boolean): ContentTransform {
    if (reduceMotion) return fadeIn(snap()) togetherWith fadeOut(snap())
    val direction = if (forward) 1 else -1
    return (
        slideInHorizontally(UCloneMotion.page<IntOffset>()) { offsetPx * direction } +
            fadeIn(UCloneMotion.page())
        ) togetherWith (
        slideOutHorizontally(UCloneMotion.page<IntOffset>()) { -offsetPx * direction } +
            fadeOut(UCloneMotion.effects())
        )
}

/* =============================================================================================
 * Rolling number: digits roll up when increasing and down when decreasing.
 * ============================================================================================= */

@Composable
fun AnimatedCount(
    value: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = LocalContentColor.current,
    format: (Long) -> String = { it.toString() },
) {
    val reduceMotion = rememberReduceMotionEnabled()
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            if (reduceMotion) {
                fadeIn(snap()) togetherWith fadeOut(snap())
            } else {
                val up = targetState > initialState
                (slideInVertically(UCloneMotion.spatial<IntOffset>()) { if (up) it else -it } + fadeIn(UCloneMotion.effects())) togetherWith
                    (slideOutVertically(UCloneMotion.spatial<IntOffset>()) { if (up) -it else it } + fadeOut(UCloneMotion.effects()))
            }
        },
        label = "animatedCount",
    ) { target ->
        Text(format(target), style = style, color = color)
    }
}

/* =============================================================================================
 * Empty / loading / error state: 64dp tonal circle + headline + one subhead + optional action.
 * ============================================================================================= */

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.INFO,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.ucloneColors.tone(tone)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .staggerEnter()
            .padding(vertical = UCloneSpacing.lg, horizontal = UCloneSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(UCloneSpacing.xs),
    ) {
        Box(
            Modifier
                .size(64.dp)
                .background(colors.container, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(UCloneSpacing.xxs))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(UCloneSpacing.xxs))
            CompactActionButton(text = actionLabel, onClick = onAction)
        }
    }
}

/* =============================================================================================
 * Bottom sheet. All confirmations, pickers and forms use this. SheetHost keeps the last value
 * composed while the 220ms exit plays, so callers can clear their state immediately.
 * ============================================================================================= */

private val LocalSheetVisibility = staticCompositionLocalOf<MutableTransitionState<Boolean>?> { null }

@Composable
fun <T : Any> SheetHost(value: T?, content: @Composable (T) -> Unit) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = value != null
    var retained by remember { mutableStateOf(value) }
    SideEffect { if (value != null) retained = value }
    val shown = value ?: retained
    if (shown != null && (visibility.currentState || visibility.targetState)) {
        CompositionLocalProvider(LocalSheetVisibility provides visibility) {
            content(shown)
        }
    } else if (value == null && retained != null && visibility.isIdle) {
        SideEffect { retained = null }
    }
}

@Composable
fun VisibleSheetHost(visible: Boolean, content: @Composable () -> Unit) {
    SheetHost(if (visible) Unit else null) { content() }
}

/**
 * Bottom sheet with the AlertDialog slot API: top corners 28dp, 36×4dp drag handle, drag-down /
 * scrim tap / predictive back to dismiss, spring entrance and 220ms accelerating exit.
 */
@Composable
fun UCloneAlertSheet(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val hosted = LocalSheetVisibility.current
    val visibility = hosted ?: remember { MutableTransitionState(false) }.apply { targetState = true }
    val reduceMotion = rememberReduceMotionEnabled()
    val dismiss = { if (visibility.targetState) onDismissRequest() }
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setDimAmount(0f)
            window?.setWindowAnimations(0)
        }
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        var dragOffset by remember { mutableFloatStateOf(0f) }
        var backProgress by remember { mutableFloatStateOf(0f) }
        val backShiftPx = with(density) { 48.dp.toPx() }
        val dismissThresholdPx = with(density) { 120.dp.toPx() }
        PredictiveBackHandler(enabled = visibility.targetState) { events ->
            try {
                events.collect { backProgress = it.progress }
                dismiss()
            } catch (cancelled: CancellationException) {
                backProgress = 0f
                throw cancelled
            }
        }
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visibleState = visibility,
                enter = fadeIn(if (reduceMotion) snap() else UCloneMotion.scrim()),
                exit = fadeOut(if (reduceMotion) snap() else UCloneMotion.exit()),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.ucloneColors.scrim)
                        .clickable(interactionSource = null, indication = null, onClick = dismiss),
                )
            }
            AnimatedVisibility(
                visibleState = visibility,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .statusBarsPadding()
                    .padding(top = UCloneSpacing.lg),
                enter = if (reduceMotion) fadeIn(snap()) else {
                    slideInVertically(UCloneMotion.sheetEnter<IntOffset>()) { it } + fadeIn(UCloneMotion.effects())
                },
                exit = if (reduceMotion) fadeOut(snap()) else {
                    slideOutVertically(UCloneMotion.exit<IntOffset>()) { it } + fadeOut(UCloneMotion.exit())
                },
            ) {
                val hairline = MaterialTheme.ucloneColors.separator
                Surface(
                    modifier = modifier
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .graphicsLayer { translationY = dragOffset + backProgress * backShiftPx }
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                            },
                            onDragStopped = { velocity ->
                                if (dragOffset > dismissThresholdPx || velocity > 1800f) {
                                    dismiss()
                                } else {
                                    scope.launch {
                                        animate(dragOffset, 0f, animationSpec = UCloneMotion.spatial()) { v, _ -> dragOffset = v }
                                    }
                                }
                            },
                        )
                        .drawBehind {
                            drawLine(hairline, Offset(size.width * 0.08f, 0f), Offset(size.width * 0.92f, 0f), strokeWidth = 1f)
                        },
                    shape = RoundedCornerShape(topStart = UCloneRadius.sheet, topEnd = UCloneRadius.sheet),
                    color = MaterialTheme.ucloneColors.raised,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Column(
                        Modifier
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(start = UCloneSpacing.lg, end = UCloneSpacing.lg, bottom = UCloneSpacing.md),
                    ) {
                        Box(
                            Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = UCloneSpacing.xs, bottom = UCloneSpacing.md)
                                .size(width = 36.dp, height = 4.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = UCloneAlpha.tertiary * 0.6f), CircleShape),
                        )
                        if (title != null) {
                            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.titleLarge) {
                                Box(Modifier.semantics { heading() }) { title() }
                            }
                            Spacer(Modifier.height(UCloneSpacing.sm))
                        }
                        if (text != null) {
                            CompositionLocalProvider(
                                LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                            ) {
                                Box(
                                    Modifier
                                        .weight(1f, fill = false)
                                        .verticalScroll(rememberScrollState()),
                                ) { text() }
                            }
                        }
                        Spacer(Modifier.height(UCloneSpacing.lg))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(UCloneSpacing.xs, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            dismissButton?.invoke()
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}

/* =============================================================================================
 * Status chip with tone color cross-fade and fade-through label.
 * ============================================================================================= */

@Composable
fun ToneChip(label: String, tone: StatusTone, modifier: Modifier = Modifier, showDot: Boolean = false) {
    val target = MaterialTheme.ucloneColors.tone(tone)
    val container by androidx.compose.animation.animateColorAsState(target.container, motionSpec(UCloneMotion.effects()), label = "chipContainer")
    val content by androidx.compose.animation.animateColorAsState(target.content, motionSpec(UCloneMotion.effects()), label = "chipContent")
    val reduceMotion = rememberReduceMotionEnabled()
    Surface(shape = CircleShape, color = container, contentColor = content, modifier = modifier) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showDot) Box(Modifier.size(7.dp).background(content, CircleShape))
            AnimatedContent(
                targetState = label,
                transitionSpec = {
                    if (reduceMotion) fadeIn(snap()) togetherWith fadeOut(snap())
                    else fadeIn(UCloneMotion.effects()) togetherWith fadeOut(UCloneMotion.effects())
                },
                label = "chipLabel",
            ) { text ->
                Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

