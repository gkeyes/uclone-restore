package com.uclone.restore.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.uclone.restore.model.RiskLevel
import com.uclone.restore.model.StepStatus

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun StatusChip(ok: Boolean, label: String) {
    AssistChip(
        onClick = {},
        label = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
    )
}

@Composable
fun RiskChip(risk: RiskLevel) {
    val label = when (risk) {
        RiskLevel.NORMAL -> "普通"
        RiskLevel.HIGH -> "高风险"
        RiskLevel.SYSTEM -> "系统 App"
    }
    StatusChip(ok = risk == RiskLevel.NORMAL, label = label)
}

@Composable
fun StepIcon(status: StepStatus) {
    val icon = when (status) {
        StepStatus.SUCCESS -> Icons.Default.CheckCircle
        StepStatus.FAILED -> Icons.Default.Error
        else -> Icons.Default.HourglassTop
    }
    val tint = when (status) {
        StepStatus.SUCCESS -> MaterialTheme.colorScheme.primary
        StepStatus.FAILED -> MaterialTheme.colorScheme.error
        StepStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
        StepStatus.PENDING -> MaterialTheme.colorScheme.outline
    }
    Icon(icon, contentDescription = null, tint = tint)
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
                .size(42.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(packageName.take(1).uppercase())
        }
    } else {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier.size(42.dp))
    }
}

fun PackageManager.safeLabel(packageName: String): String =
    runCatching { getApplicationLabel(getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)

@Composable
fun VerticalGap() {
    Spacer(Modifier.height(12.dp))
}
