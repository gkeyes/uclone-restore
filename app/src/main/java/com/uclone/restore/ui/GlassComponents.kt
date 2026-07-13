package com.uclone.restore.ui

import android.os.Build
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens

internal enum class GlassRole {
    Navigation,
    SelectionLens,
    ToolbarControl,
    PrimaryAction,
}

private data class GlassSpec(
    val blurDp: Float,
    val refractionHeightDp: Float,
    val refractionAmountDp: Float,
    val surfaceAlpha: Float,
    val saturation: Float,
)

private val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
internal fun GlassBackdropHost(
    modifier: Modifier = Modifier,
    background: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
        Box(modifier) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .layerBackdrop(backdrop),
                content = background,
            )
            overlay()
        }
    }
}

@Composable
internal fun LiquidGlassSurface(
    role: GlassRole,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    tint: Color = Color.Unspecified,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalGlassBackdrop.current
    val density = LocalDensity.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val reduceMotion = rememberReduceMotionEnabled()
    val scale by animateFloatAsState(
        targetValue = if (!reduceMotion && pressed && enabled && onClick != null) 0.97f else 1f,
        animationSpec = if (reduceMotion) androidx.compose.animation.core.snap() else tween(150),
        label = "liquidGlassPress",
    )
    val shape = role.shape()
    val spec = role.spec()
    val surfaceColor = if (tint.isSpecified) {
        tint.copy(alpha = spec.surfaceAlpha)
    } else {
        when (role) {
            GlassRole.SelectionLens,
            GlassRole.PrimaryAction,
            -> MaterialTheme.colorScheme.primary.copy(alpha = spec.surfaceAlpha)

            GlassRole.Navigation,
            GlassRole.ToolbarControl,
            -> MaterialTheme.ucloneColors.navigationSurface.copy(alpha = spec.surfaceAlpha)
        }
    }
    val opticalModifier = if (backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val blurPx = with(density) { spec.blurDp.dp.toPx() }
        val refractionHeightPx = with(density) { spec.refractionHeightDp.dp.toPx() }
        val refractionAmountPx = with(density) { spec.refractionAmountDp.dp.toPx() }
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                colorControls(saturation = spec.saturation)
                blur(blurPx)
                lens(
                    refractionHeight = refractionHeightPx,
                    refractionAmount = refractionAmountPx,
                    depthEffect = true,
                    chromaticAberration = false,
                )
            },
            layerBlock = {
                scaleX = scale
                scaleY = scale
            },
            onDrawSurface = { drawRect(surfaceColor) },
        )
    } else {
        Modifier
            .background(surfaceColor, shape)
            .border(
                width = 0.5.dp,
                color = MaterialTheme.ucloneColors.glassHighlight.copy(alpha = 0.72f),
                shape = shape,
            )
    }
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .then(opticalModifier)
            .then(clickableModifier)
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

@Composable
internal fun rememberReduceMotionEnabled(): Boolean {
    val context = LocalContext.current
    var reduceMotion by remember(context) {
        mutableStateOf(context.contentResolver.animatorDurationScale() == 0f)
    }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduceMotion = resolver.animatorDurationScale() == 0f
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduceMotion
}

private fun android.content.ContentResolver.animatorDurationScale(): Float = runCatching {
    Settings.Global.getFloat(this, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}.getOrDefault(1f)

private fun GlassRole.shape(): Shape = when (this) {
    GlassRole.Navigation -> RoundedCornerShape(30.dp)
    GlassRole.SelectionLens -> RoundedCornerShape(24.dp)
    GlassRole.ToolbarControl -> RoundedCornerShape(24.dp)
    GlassRole.PrimaryAction -> RoundedCornerShape(24.dp)
}

private fun GlassRole.spec(): GlassSpec = when (this) {
    GlassRole.Navigation -> GlassSpec(
        blurDp = 16f,
        refractionHeightDp = 18f,
        refractionAmountDp = 16f,
        surfaceAlpha = 0.18f,
        saturation = 1.08f,
    )

    GlassRole.SelectionLens -> GlassSpec(
        blurDp = 8f,
        refractionHeightDp = 12f,
        refractionAmountDp = 14f,
        surfaceAlpha = 0.12f,
        saturation = 1.06f,
    )

    GlassRole.ToolbarControl -> GlassSpec(
        blurDp = 12f,
        refractionHeightDp = 12f,
        refractionAmountDp = 16f,
        surfaceAlpha = 0.16f,
        saturation = 1.06f,
    )

    GlassRole.PrimaryAction -> GlassSpec(
        blurDp = 8f,
        refractionHeightDp = 12f,
        refractionAmountDp = 18f,
        surfaceAlpha = 0.16f,
        saturation = 1.06f,
    )
}

@Preview(showBackground = true, widthDp = 360, heightDp = 180)
@Composable
private fun LiquidGlassPreview() {
    UCloneTheme {
        GlassBackdropHost(
            modifier = Modifier.fillMaxSize(),
            background = {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repeat(3) { index ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .background(
                                    if (index == 1) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.ucloneColors.groupedSurface,
                                    RoundedCornerShape(12.dp),
                                ),
                        ) {}
                    }
                }
            },
            overlay = {
                LiquidGlassSurface(
                    role = GlassRole.Navigation,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .fillMaxWidth()
                        .height(64.dp),
                ) {
                    Text("首页   App   数据   历史   设置")
                }
                LiquidGlassSurface(
                    role = GlassRole.SelectionLens,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 18.dp, bottom = 18.dp)
                        .size(width = 58.dp, height = 52.dp),
                    tint = MaterialTheme.colorScheme.primary,
                ) {}
            },
        )
    }
}
