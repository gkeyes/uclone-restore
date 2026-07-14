package com.uclone.restore.ui

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
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

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.ucloneColors.groupedSurface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 0.dp,
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
fun PageDescription(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
    )
}

@Composable
fun SectionLabel(title: String, caption: String? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 8.dp, end = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
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
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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

@Composable
fun StatusChip(ok: Boolean, label: String) {
    val colors = MaterialTheme.ucloneColors
    val color = if (ok) colors.success else MaterialTheme.colorScheme.onErrorContainer
    val dotColor = if (ok) colors.switchOn else MaterialTheme.colorScheme.error
    val container = if (ok) colors.successContainer else MaterialTheme.colorScheme.errorContainer
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = container,
        contentColor = color,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(dotColor, CircleShape))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun RiskChip(risk: RiskLevel) {
    val colors = MaterialTheme.ucloneColors
    val (label, color, container) = when (risk) {
        RiskLevel.NORMAL -> Triple("普通", colors.success, colors.successContainer)
        RiskLevel.HIGH -> Triple("高风险", colors.warning, colors.warningContainer)
        RiskLevel.SYSTEM -> Triple(
            "系统 App",
            MaterialTheme.colorScheme.onErrorContainer,
            MaterialTheme.colorScheme.errorContainer,
        )
    }
    Surface(shape = RoundedCornerShape(999.dp), color = container, contentColor = color) {
        Text(
            label,
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun StepIcon(status: StepStatus) {
    val colors = MaterialTheme.ucloneColors
    val icon = when (status) {
        StepStatus.SUCCESS -> Icons.Default.Check
        StepStatus.FAILED -> Icons.Default.Close
        else -> Icons.Default.HourglassTop
    }
    val tint = when (status) {
        StepStatus.SUCCESS -> colors.success
        StepStatus.FAILED -> MaterialTheme.colorScheme.error
        StepStatus.RUNNING -> colors.warning
        StepStatus.PENDING -> colors.neutral
    }
    Surface(shape = CircleShape, color = tint.copy(alpha = 0.12f), contentColor = tint) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(5.dp).size(16.dp))
    }
}

@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
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
                .size(40.dp)
                .background(MaterialTheme.ucloneColors.elevatedSurface, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(packageName.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
        }
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.size(40.dp).clip(RoundedCornerShape(11.dp)),
        )
    }
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
        val cacheKey = "$packageName:${packageManager.lastUpdateTime(packageName)}"
        bitmaps.get(cacheKey)?.asImageBitmap() ?: runCatching {
            packageManager.getApplicationIcon(packageName).toBitmap(96, 96)
        }.getOrNull()?.also { bitmaps.put(cacheKey, it) }?.asImageBitmap()
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.lastUpdateTime(packageName: String): Long =
        runCatching { getPackageInfo(packageName, 0).lastUpdateTime }.getOrDefault(0L)
}

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
    val containerColor = when {
        !enabled -> MaterialTheme.ucloneColors.elevatedSurface.copy(alpha = 0.5f)
        primary -> MaterialTheme.ucloneColors.actionPrimary
        danger -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.76f)
        else -> MaterialTheme.ucloneColors.elevatedSurface
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        primary -> MaterialTheme.ucloneColors.onActionPrimary
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val controlModifier = modifier
        .heightIn(min = 48.dp)
        .widthIn(min = 72.dp)
        .semantics {
            role = Role.Button
            if (danger) stateDescription = "危险操作"
        }
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = controlModifier.pressScale(interactionSource, enabled),
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
        interactionSource = interactionSource,
        border = when {
            !enabled -> null
            primary -> null
            danger -> BorderStroke(0.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.16f))
            else -> null
        },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
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
    Text(text, style = MaterialTheme.typography.labelLarge)
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
            .heightIn(min = 48.dp)
            .pressScale(interactionSource, enabled)
            .semantics {
                role = Role.Button
                if (danger) stateDescription = "危险操作"
            },
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        color = Color.Transparent,
        contentColor = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            danger -> MaterialTheme.colorScheme.error
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
                .size(48.dp)
                .pressScale(interactionSource, enabled),
            enabled = enabled,
            shape = CircleShape,
            color = if (selected) {
                tint.copy(alpha = 0.12f)
            } else {
                MaterialTheme.ucloneColors.elevatedSurface.copy(alpha = 0.88f)
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
            .size(48.dp)
            .pressScale(interactionSource, enabled),
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
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
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
    showDivider: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (primary) {
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
        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.ucloneColors.separator.copy(alpha = 0.45f))
        }
    }
}

@Composable
fun UCloneSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val reduceMotion = rememberReduceMotionEnabled()
    val interactionSource = remember { MutableInteractionSource() }
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 0.dp,
        animationSpec = if (reduceMotion) snap() else tween(180),
        label = "ucloneSwitchThumb",
    )
    val trackColor = if (checked) MaterialTheme.ucloneColors.switchOn else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .width(55.dp)
            .height(48.dp)
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(51.dp)
                .height(31.dp)
                .pressScale(interactionSource, enabled)
                .clip(CircleShape)
                .background(trackColor.copy(alpha = if (enabled) 1f else 0.45f))
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
private fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduceMotion = rememberReduceMotionEnabled()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reduceMotion) 0.97f else 1f,
        animationSpec = if (reduceMotion) snap() else tween(150),
        label = "controlPressScale",
    )
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed && enabled && reduceMotion) 0.82f else 1f,
        animationSpec = tween(150),
        label = "controlPressFade",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        alpha = pressAlpha
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
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        shape = MaterialTheme.shapes.medium,
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

@Composable
fun DialogActionButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                role = Role.Button
                if (danger) stateDescription = "危险操作"
            },
        colors = ButtonDefaults.textButtonColors(
            contentColor = when {
                danger -> MaterialTheme.colorScheme.error
                primary -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

fun PackageManager.safeLabel(packageName: String): String =
    runCatching { getApplicationLabel(getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)

@Composable
fun VerticalGap() {
    Spacer(Modifier.height(12.dp))
}
