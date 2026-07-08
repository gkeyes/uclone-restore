package com.uclone.restore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uclone.restore.model.UCloneSettings

@Composable
fun SettingsScreen(state: UiState, viewModel: UCloneViewModel, modifier: Modifier) {
    var draft by remember(state.settings) { mutableStateOf(state.settings) }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        SectionCard("用户 ID") {
            NumberField("主系统 ID", draft.mainUserId) { draft = draft.copy(mainUserId = it) }
            NumberField("分身系统 ID", draft.cloneUserId) { draft = draft.copy(cloneUserId = it) }
        }
        SectionCard("路径") {
            OutlinedTextField(
                value = draft.rootDir,
                onValueChange = { draft = draft.copy(rootDir = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Root 数据目录") },
                singleLine = true,
            )
            Text("${draft.rootDir}/snapshots")
            Text("${draft.rootDir}/rollback")
            Text("${draft.rootDir}/logs")
        }
        SectionCard("默认数据范围") {
            ToggleRow("CE 数据", draft.includeCe) { draft = draft.copy(includeCe = it) }
            ToggleRow("DE 数据", draft.includeDe) { draft = draft.copy(includeDe = it) }
            ToggleRow("external", draft.includeExternal) { draft = draft.copy(includeExternal = it) }
            ToggleRow("media", draft.includeMedia) { draft = draft.copy(includeMedia = it) }
            ToggleRow("obb", draft.includeObb) { draft = draft.copy(includeObb = it) }
            ToggleRow("排除 cache/code_cache", draft.excludeCache) { draft = draft.copy(excludeCache = it) }
        }
        Button(onClick = { viewModel.saveSettings(draft) }) {
            Text("保存设置")
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text -> text.toIntOrNull()?.let(onChange) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
