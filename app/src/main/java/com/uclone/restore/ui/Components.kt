package com.uclone.restore.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uclone.restore.model.RiskLevel
import com.uclone.restore.model.StepStatus

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    glass: Boolean = true,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (glass) IosGlass else IosGroup),
        border = BorderStroke(1.dp, if (glass) IosGlassBorder else IosSeparator.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.42f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            modifier = Modifier.weight(0.58f),
            color = valueColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun StatusChip(ok: Boolean, label: String) {
    val color = if (ok) IosGreen else IosRed
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(color, RoundedCornerShape(999.dp)),
            )
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun RiskChip(risk: RiskLevel) {
    val (label, color) = when (risk) {
        RiskLevel.NORMAL -> "普通" to IosGreen
        RiskLevel.HIGH -> "高风险" to IosOrange
        RiskLevel.SYSTEM -> "系统 App" to IosRed
    }
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
        )
    }
}

@Composable
fun StepIcon(status: StepStatus) {
    val icon = when (status) {
        StepStatus.SUCCESS -> Icons.Default.Check
        StepStatus.FAILED -> Icons.Default.Close
        else -> Icons.Default.HourglassTop
    }
    val tint = when (status) {
        StepStatus.SUCCESS -> IosGreen
        StepStatus.FAILED -> IosRed
        StepStatus.RUNNING -> IosOrange
        StepStatus.PENDING -> MaterialTheme.colorScheme.outline
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = tint.copy(alpha = 0.12f),
        contentColor = tint,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(5.dp).size(14.dp))
    }
}

@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val packageManager = context.applicationContext.packageManager
    val cachedIcon = remember(packageName) { ApplicationIconCache[packageName] }
    val icon by produceState<CachedAppIcon?>(cachedIcon, packageName, packageManager) {
        if (value == null) {
            value = loadApplicationIcon(packageManager, packageName)
        }
    }

    when (val cached = icon) {
        is CachedAppIcon.Loaded -> {
            Image(bitmap = cached.bitmap, contentDescription = null, modifier = modifier.size(36.dp))
        }

        CachedAppIcon.Missing,
        null,
        -> {
            Box(
                modifier
                    .size(36.dp)
                    .background(IosGlassRaised, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(packageName.take(1).uppercase())
            }
        }
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun IosPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = IosBlue,
            contentColor = Color.White,
            disabledContainerColor = IosSeparator,
            disabledContentColor = IosSecondaryText,
        ),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 2.dp,
            pressedElevation = 0.dp,
            disabledElevation = 0.dp,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        content = content,
    )
}

@Composable
fun IosSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = IosGlassControl,
            contentColor = IosText,
            disabledContentColor = IosSecondaryText,
        ),
        border = BorderStroke(1.dp, IosControlBorder),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 1.dp,
            pressedElevation = 0.dp,
            disabledElevation = 0.dp,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        content = content,
    )
}

@Composable
fun IosCompactButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    danger: Boolean = false,
    semanticTint: Color? = null,
    icon: ImageVector? = null,
) {
    val accentColor = when {
        danger -> IosRed
        semanticTint != null -> semanticTint
        else -> IosBlue
    }
    val contentColor = when {
        primary -> Color.White
        else -> accentColor
    }
    val containerColor = when {
        primary -> accentColor
        else -> IosGlassControl
    }
    val borderColor = when {
        primary -> Color.White.copy(alpha = 0.28f)
        danger || semanticTint != null -> accentColor.copy(alpha = 0.22f)
        else -> IosControlBorder
    }
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 36.dp)
            .widthIn(min = 58.dp),
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = IosGlassRaised,
            disabledContentColor = IosTertiaryText,
        ),
        border = BorderStroke(1.dp, borderColor),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 1.dp,
            pressedElevation = 0.dp,
            disabledElevation = 0.dp,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun IosGlassIconButton(
    imageVector: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = IosText,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val containerColor = if (selected) tint.copy(alpha = 0.12f) else IosGlassControl
    val borderColor = if (selected) tint.copy(alpha = 0.22f) else IosControlBorder
    Surface(
        onClick = onClick,
        modifier = modifier.size(40.dp),
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = tint,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = 0.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(imageVector, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun IosStatusPill(label: String, color: Color = IosSecondaryText) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.10f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.18f)),
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
fun SingleLinePathText(path: String, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    SelectionContainer {
        Text(
            path,
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
fun IosDialogButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    danger: Boolean = false,
    semanticTint: Color? = null,
) {
    IosCompactButton(
        text = text,
        onClick = onClick,
        primary = primary,
        danger = danger,
        semanticTint = semanticTint,
        modifier = Modifier.widthIn(min = 72.dp),
    )
}

fun PackageManager.safeLabel(packageName: String): String =
    runCatching { getApplicationLabel(getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)

@Composable
fun VerticalGap() {
    Spacer(Modifier.height(12.dp))
}
