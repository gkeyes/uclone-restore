package com.uclone.restore.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.uclone.restore.model.RiskLevel
import com.uclone.restore.model.StepStatus

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}

@Composable
fun PageDescription(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, end = 4.dp, bottom = 2.dp),
    )
}

@Composable
fun SectionLabel(title: String, caption: String? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 8.dp, end = 4.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
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
            .heightIn(min = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.42f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            value,
            modifier = Modifier.weight(0.58f),
            color = valueColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun StatusChip(ok: Boolean, label: String) {
    val colors = MaterialTheme.ucloneColors
    val color = if (ok) colors.success else MaterialTheme.colorScheme.error
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
            Box(Modifier.size(7.dp).background(color, CircleShape))
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
        RiskLevel.SYSTEM -> Triple("系统 App", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
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
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
    if (bitmap == null) {
        Box(
            modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(packageName.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
        }
    } else {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier.size(40.dp))
    }
}

@Composable
fun PrimaryActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
fun SecondaryActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
        border = BorderStroke(1.dp, if (danger) contentColor.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
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
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp).widthIn(min = 72.dp),
            enabled = enabled,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) {
            ActionButtonContent(text, icon)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp).widthIn(min = 72.dp),
            enabled = enabled,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            ),
            border = BorderStroke(
                1.dp,
                if (danger) MaterialTheme.colorScheme.error.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline,
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
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
fun UtilityIconButton(
    imageVector: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        enabled = enabled,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else tint,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(imageVector, contentDescription = contentDescription, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
fun StatusBadge(label: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.22f)),
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
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
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
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            CompactActionButton(
                text = actionLabel,
                onClick = onClick,
                enabled = enabled,
                primary = primary,
                danger = danger,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    }
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
) {
    TextButton(
        onClick = onClick,
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
