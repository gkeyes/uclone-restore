package com.uclone.restore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun RestoreCompatibilityControls(
    allowVersionMismatch: Boolean,
    allowLegacyIdentity: Boolean,
    onAllowVersionMismatchChange: (Boolean) -> Unit,
    onAllowLegacyIdentityChange: (Boolean) -> Unit,
) {
    CompatibilityToggle(
        title = "允许跨版本恢复",
        description = "仅在备份与当前 App 版本不一致时放行；签名证书仍必须一致。",
        checked = allowVersionMismatch,
        onCheckedChange = onAllowVersionMismatchChange,
    )
    CompatibilityToggle(
        title = "允许旧版未验证备份",
        description = "旧备份缺少证书信息，无法证明包身份；只应在明确确认来源时使用。",
        checked = allowLegacyIdentity,
        onCheckedChange = onAllowLegacyIdentityChange,
    )
}

@Composable
private fun CompatibilityToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = IosGroup,
                checkedTrackColor = IosOrange,
                uncheckedThumbColor = IosGroup,
                uncheckedTrackColor = IosSeparator,
                uncheckedBorderColor = IosSeparator,
            ),
        )
    }
}
