package com.uclone.restore.ui

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.core.graphics.drawable.toBitmap
import com.uclone.restore.model.RiskLevel
import com.uclone.restore.model.StepStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalBottomBarContentPadding = staticCompositionLocalOf { 16.dp }
internal val LocalTopBarContentPadding = staticCompositionLocalOf { 16.dp }

enum class ToolRowActionVariant {
    Auto,
    Pill,
}

@Composable
internal fun useStackedLayoutForLargeText(): Boolean = LocalDensity.current.fontScale >= 1.3f

@Composable
internal fun ScrollableTopLevelContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    state: LazyListState = rememberLazyListState(),
    header: @Composable () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    ReportTitleCollapse(state)
    LazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
    ) {
        item(key = "top_level_header") { header() }
        content()
    }
}

@Composable
fun TopLevelHeader(
    title: String,
    description: String,
    taskActive: Boolean = false,
    onOpenHistory: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("uclone_top_level_header")
            .staggerEnter(),
        verticalArrangement = Arrangement.spacedBy(UCloneSpacing.xxs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UCloneBrandIcon()
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (taskActive) {
                UtilityIconButton(
                    imageVector = Icons.Default.PendingActions,
                    contentDescription = "查看当前任务",
                    onClick = onOpenHistory,
                    tint = MaterialTheme.colorScheme.primary,
                    framed = true,
                )
            }
        }
        Text(
            text = description,
            modifier = Modifier.padding(horizontal = 2.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .staggerEnter(),
        verticalArrangement = Arrangement.spacedBy(UCloneSpacing.xs),
    ) {
        SectionHeaderText(title)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.ucloneColors.groupedSurface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 0.dp,
        ) {
            Column(
                Modifier
                    .animateContentSize(motionSpec(UCloneMotion.spatial()))
                    .padding(horizontal = UCloneSpacing.cardPadding, vertical = UCloneSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                content()
            }
        }
    }
}

/** Section header: 13sp Medium, secondary color, above the card and aligned with card text. */
@Composable
internal fun SectionHeaderText(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier.padding(start = UCloneSpacing.cardPadding, end = UCloneSpacing.cardPadding),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun PageDescription(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun SectionLabel(title: String, caption: String? = null, count: Int? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = UCloneSpacing.cardPadding, top = UCloneSpacing.xs, end = UCloneSpacing.cardPadding, bottom = UCloneSpacing.xxs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (count != null) {
                AnimatedCount(
                    value = count.toLong(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = UCloneAlpha.tertiary / UCloneAlpha.secondary),
                )
            }
        }
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (useStackedLayoutForLargeText()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                value,
                color = valueColor,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = UCloneSpacing.rowMinHeight),
            horizontalArrangement = Arrangement.spacedBy(UCloneSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                modifier = Modifier.weight(0.48f),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                value,
                modifier = Modifier.weight(0.52f),
                color = valueColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun StatusChip(ok: Boolean, label: String) {
    ToneChip(label = label, tone = if (ok) StatusTone.SUCCESS else StatusTone.DANGER, showDot = true)
}

@Composable
fun RiskChip(risk: RiskLevel) {
    val (label, tone) = when (risk) {
        RiskLevel.NORMAL -> "普通" to StatusTone.SUCCESS
        RiskLevel.HIGH -> "高风险" to StatusTone.WARNING
        RiskLevel.SYSTEM -> "系统 App" to StatusTone.DANGER
    }
    ToneChip(label = label, tone = tone)
}

@Composable
fun StepIcon(status: StepStatus) {
    val colors = MaterialTheme.ucloneColors
    val icon = when (status) {
        StepStatus.SUCCESS -> Icons.Default.Check
        StepStatus.FAILED -> Icons.Default.Close
        else -> Icons.Default.HourglassTop
    }
    val tone = colors.tone(
        when (status) {
            StepStatus.SUCCESS -> StatusTone.SUCCESS
            StepStatus.FAILED -> StatusTone.DANGER
            StepStatus.RUNNING -> StatusTone.WARNING
            StepStatus.PENDING -> StatusTone.NEUTRAL
        },
    )
    val tint by animateColorAsState(tone.content, motionSpec(UCloneMotion.effects()), label = "stepTint")
    val container by animateColorAsState(tone.container, motionSpec(UCloneMotion.effects()), label = "stepContainer")
    Surface(shape = CircleShape, color = container, contentColor = tint) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(5.dp).size(16.dp))
    }
}

@Composable
fun AppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    cornerRadius: Dp = UCloneRadius.small,
) {
    val packageManager = LocalContext.current.applicationContext.packageManager
    val loadedState = produceState<LoadedAppIcon?>(
        initialValue = null,
        packageName,
        packageManager,
    ) {
        value = null
        value = AppIconCache.load(packageManager, packageName)?.let { LoadedAppIcon(packageName, it) }
    }
    val bitmap = loadedState.value
        ?.takeIf { shouldDisplayAppIcon(packageName, it.packageName) }
        ?.bitmap
    if (bitmap == null) {
        Box(
            modifier
                .size(size)
                .background(MaterialTheme.ucloneColors.elevatedSurface, RoundedCornerShape(cornerRadius)),
            contentAlignment = Alignment.Center,
        ) {
            Text(packageName.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
        }
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.size(size).clip(RoundedCornerShape(cornerRadius)),
        )
    }
}

@Composable
fun UCloneBrandIcon(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val context = LocalContext.current.applicationContext
    val bitmap = remember(context.packageManager, context.packageName) {
        requireNotNull(AppIconCache.loadNow(context.packageManager, context.packageName)) {
            "UClone launcher icon is unavailable"
        }
    }
    Image(
        bitmap = bitmap,
        contentDescription = "UClone 桌面图标",
        modifier = modifier
            .testTag("uclone_brand_icon")
            .size(size)
            .clip(RoundedCornerShape(12.dp)),
    )
}

internal fun shouldDisplayAppIcon(requestedPackageName: String, loadedPackageName: String?): Boolean =
    requestedPackageName == loadedPackageName

private data class LoadedAppIcon(
    val packageName: String,
    val bitmap: ImageBitmap,
)

private object AppIconCache {
    private const val MAX_CACHE_KB = 4 * 1024
    private val bitmaps = object : LruCache<String, Bitmap>(MAX_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            maxOf(1, value.allocationByteCount / 1024)
    }

    suspend fun load(packageManager: PackageManager, packageName: String): ImageBitmap? = withContext(Dispatchers.IO) {
        loadNow(packageManager, packageName)
    }

    fun loadNow(packageManager: PackageManager, packageName: String): ImageBitmap? {
        val cacheKey = "$packageName:${packageManager.lastUpdateTime(packageName)}"
        return bitmaps.get(cacheKey)?.asImageBitmap() ?: runCatching {
            packageManager.getApplicationIcon(packageName).toBitmap(96, 96)
        }.getOrNull()?.also { bitmaps.put(cacheKey, it) }?.asImageBitmap()
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.lastUpdateTime(packageName: String): Long =
        runCatching { getPackageInfo(packageName, 0).lastUpdateTime }.getOrDefault(0L)
}

/**
 * Spec buttons: primary = filled accent, secondary = tonal (accent 12%), destructive = danger
 * tone. Corner 16, ≥48dp, press feedback; primary and destructive give a confirm haptic.
 */
@Composable
fun CompactActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    danger: Boolean = false,
    icon: ImageVector? = null,
) {
    val colors = MaterialTheme.ucloneColors
    val haptic = rememberHaptics()
    val containerTarget = when {
        !enabled -> colors.elevatedSurface
        primary -> colors.actionPrimary
        danger -> colors.dangerTone.container
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentTarget = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = UCloneAlpha.disabled)
        primary -> colors.onActionPrimary
        danger -> colors.dangerTone.content
        else -> MaterialTheme.colorScheme.primary
    }
    val containerColor by animateColorAsState(containerTarget, motionSpec(UCloneMotion.effects()), label = "buttonContainer")
    val contentColor by animateColorAsState(contentTarget, motionSpec(UCloneMotion.effects()), label = "buttonContent")
    val controlModifier = modifier
        .heightIn(min = UCloneSpacing.touchTarget)
        .widthIn(min = 72.dp)
        .semantics {
            role = Role.Button
            if (danger) stateDescription = "危险操作"
        }
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = {
            if (primary || danger) haptic(UCloneHaptic.Confirm)
            onClick()
        },
        modifier = controlModifier.pressFeedback(interactionSource, enabled),
        enabled = enabled,
        shape = RoundedCornerShape(UCloneRadius.button),
        color = containerColor,
        contentColor = contentColor,
        interactionSource = interactionSource,
    ) {
        Row(
            Modifier.padding(horizontal = UCloneSpacing.md, vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionButtonContent(text, icon)
        }
    }
}

@Composable
private fun ActionButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
}

@Composable
fun InlineActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    icon: ImageVector? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = UCloneSpacing.touchTarget)
            .pressFeedback(interactionSource, enabled)
            .semantics {
                role = Role.Button
                if (danger) stateDescription = "危险操作"
            },
        enabled = enabled,
        shape = RoundedCornerShape(UCloneRadius.button),
        color = Color.Transparent,
        contentColor = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = UCloneAlpha.disabled)
            danger -> MaterialTheme.ucloneColors.dangerTone.content
            else -> MaterialTheme.colorScheme.primary
        },
        interactionSource = interactionSource,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionButtonContent(text, icon)
        }
    }
}

@Composable
fun UtilityIconButton(
    imageVector: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    selected: Boolean = false,
    framed: Boolean = false,
    enabled: Boolean = true,
) {
    if (framed) {
        val interactionSource = remember { MutableInteractionSource() }
        Surface(
            onClick = onClick,
            modifier = modifier
                .size(UCloneSpacing.touchTarget)
                .pressFeedback(interactionSource, enabled),
            enabled = enabled,
            shape = CircleShape,
            color = if (selected) {
                tint.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            contentColor = tint,
            interactionSource = interactionSource,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(imageVector, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
            }
        }
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(UCloneSpacing.touchTarget)
            .pressFeedback(interactionSource, enabled),
        enabled = enabled,
        shape = CircleShape,
        color = when {
            selected -> tint.copy(alpha = 0.10f)
            else -> Color.Transparent
        },
        contentColor = tint,
        border = when {
            selected -> BorderStroke(0.5.dp, tint.copy(alpha = 0.14f))
            else -> null
        },
        interactionSource = interactionSource,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(imageVector, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun StatusBadge(label: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val animated by animateColorAsState(color, motionSpec(UCloneMotion.effects()), label = "statusBadge")
    Surface(
        shape = CircleShape,
        color = animated.copy(alpha = 0.12f),
        contentColor = animated,
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ToolRow(
    title: String,
    description: String,
    actionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    primary: Boolean = false,
    danger: Boolean = false,
    actionVariant: ToolRowActionVariant = ToolRowActionVariant.Auto,
    showDivider: Boolean = true,
) {
    val largeText = useStackedLayoutForLargeText()
    Column(modifier.fillMaxWidth()) {
        if (largeText) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    ToolRowIcon(icon = icon, primary = primary, danger = danger)
                    ToolRowText(title = title, description = description, modifier = Modifier.weight(1f))
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    ToolRowAction(
                        actionLabel = actionLabel,
                        onClick = onClick,
                        enabled = enabled,
                        primary = primary,
                        danger = danger,
                        variant = actionVariant,
                    )
                }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolRowIcon(icon = icon, primary = primary, danger = danger)
                ToolRowText(title = title, description = description, modifier = Modifier.weight(1f))
                ToolRowAction(
                    actionLabel = actionLabel,
                    onClick = onClick,
                    enabled = enabled,
                    primary = primary,
                    danger = danger,
                    variant = actionVariant,
                )
            }
        }
        if (showDivider) {
            // Inset hairline starting at the text, never a full-width divider.
            HorizontalDivider(
                modifier = Modifier.padding(start = if (icon == null) 0.dp else 48.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.ucloneColors.separator,
            )
        }
    }
}

@Composable
private fun ToolRowIcon(icon: ImageVector?, primary: Boolean, danger: Boolean) {
    if (icon == null) return
    val tone = MaterialTheme.ucloneColors.tone(
        when {
            danger -> StatusTone.DANGER
            primary -> StatusTone.INFO
            else -> StatusTone.NEUTRAL
        },
    )
    Surface(
        modifier = Modifier.size(36.dp),
        shape = RoundedCornerShape(UCloneRadius.small),
        color = tone.container,
        contentColor = tone.content,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun ToolRowText(title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToolRowAction(
    actionLabel: String,
    onClick: () -> Unit,
    enabled: Boolean,
    primary: Boolean,
    danger: Boolean,
    variant: ToolRowActionVariant,
) {
    if (variant == ToolRowActionVariant.Pill) {
        CompactActionButton(
            text = actionLabel,
            onClick = onClick,
            enabled = enabled,
            primary = primary && !danger,
            danger = danger,
            modifier = Modifier.width(72.dp),
        )
    } else if (primary) {
        CompactActionButton(
            text = actionLabel,
            onClick = onClick,
            enabled = enabled,
            primary = true,
        )
    } else {
        InlineActionButton(
            text = actionLabel,
            onClick = onClick,
            enabled = enabled,
            danger = danger,
        )
    }
}

@Composable
fun UCloneSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptic = rememberHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 0.dp,
        animationSpec = motionSpec(UCloneMotion.spatial()),
        label = "ucloneSwitchThumb",
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) {
            MaterialTheme.ucloneColors.switchOn
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
        },
        animationSpec = motionSpec(UCloneMotion.effects()),
        label = "ucloneSwitchTrack",
    )
    Box(
        modifier = modifier
            .width(55.dp)
            .height(UCloneSpacing.touchTarget)
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { value ->
                    haptic(if (value) UCloneHaptic.ToggleOn else UCloneHaptic.ToggleOff)
                    onCheckedChange(value)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(51.dp)
                .height(31.dp)
                .pressFeedback(interactionSource, enabled)
                .clip(CircleShape)
                .background(trackColor.copy(alpha = if (enabled) trackColor.alpha else UCloneAlpha.disabled))
                .padding(2.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .offset(x = thumbOffset)
                    .size(27.dp)
                    .background(Color.White, CircleShape),
            )
        }
    }
}

@Composable
fun ResponsiveSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val largeText = useStackedLayoutForLargeText()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (largeText) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                UCloneSwitch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                UCloneSwitch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                )
            }
        }
        description?.let {
            Text(
                it,
                modifier = Modifier.padding(end = 4.dp, bottom = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun GroupedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = motionSpec(UCloneMotion.effects()),
        label = "searchFieldBorder",
    )
    val controlShape = RoundedCornerShape(UCloneRadius.control)
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .border(1.5.dp, borderColor, controlShape),
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        shape = controlShape,
        singleLine = true,
        visualTransformation = visualTransformation,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.ucloneColors.elevatedSurface,
            unfocusedContainerColor = MaterialTheme.ucloneColors.elevatedSurface,
            disabledContainerColor = MaterialTheme.ucloneColors.elevatedSurface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
fun SingleLinePathText(path: String, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    SelectionContainer {
        Text(
            path,
            modifier = modifier.fillMaxWidth().horizontalScroll(scrollState),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Sheet / dialog action. Primary = filled accent, destructive = danger tone, others = text.
 * Confirming gives a confirm haptic, dismissing gives a reject haptic.
 */
@Composable
fun DialogActionButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val haptic = rememberHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val colors = MaterialTheme.ucloneColors
    if (primary || danger) {
        Surface(
            onClick = {
                haptic(UCloneHaptic.Confirm)
                onClick()
            },
            enabled = enabled,
            modifier = modifier
                .heightIn(min = 52.dp)
                .widthIn(min = 96.dp)
                .pressFeedback(interactionSource, enabled)
                .semantics {
                    role = Role.Button
                    if (danger) stateDescription = "危险操作"
                },
            shape = RoundedCornerShape(UCloneRadius.button),
            color = when {
                !enabled -> colors.elevatedSurface
                danger -> colors.dangerTone.container
                else -> colors.actionPrimary
            },
            contentColor = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = UCloneAlpha.disabled)
                danger -> colors.dangerTone.content
                else -> colors.onActionPrimary
            },
            interactionSource = interactionSource,
        ) {
            Box(Modifier.padding(horizontal = UCloneSpacing.lg, vertical = 14.dp), contentAlignment = Alignment.Center) {
                Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    } else {
        TextButton(
            onClick = {
                haptic(UCloneHaptic.Reject)
                onClick()
            },
            enabled = enabled,
            interactionSource = interactionSource,
            modifier = modifier
                .heightIn(min = 52.dp)
                .pressFeedback(interactionSource, enabled)
                .semantics { role = Role.Button },
            shape = RoundedCornerShape(UCloneRadius.button),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            contentPadding = PaddingValues(horizontal = UCloneSpacing.md, vertical = 10.dp),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

fun PackageManager.safeLabel(packageName: String): String =
    runCatching { getApplicationLabel(getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)

@Composable
fun VerticalGap() {
    Spacer(Modifier.height(12.dp))
}
