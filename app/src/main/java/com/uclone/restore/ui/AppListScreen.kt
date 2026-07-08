package com.uclone.restore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uclone.restore.model.AppEntry
import com.uclone.restore.util.Formatters

@Composable
fun AppListScreen(state: UiState, viewModel: UCloneViewModel, modifier: Modifier, openDetail: () -> Unit) {
    val query = state.search.trim().lowercase()
    val apps = state.apps.filter {
        query.isEmpty() || it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query)
    }
    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("App", "选择分身和主系统都需要处理的目标 App。")
        OutlinedTextField(
            value = state.search,
            onValueChange = viewModel::updateSearch,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("搜索包名或 App 名称") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            shape = RoundedCornerShape(14.dp),
            singleLine = true,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(apps, key = { it.packageName }) { app ->
                AppRow(app) {
                    viewModel.selectPackage(app.packageName)
                    openDetail()
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app.packageName)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(app.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "user0 ${if (app.user0Installed) "已安装" else "缺失"} · user10 ${if (app.user10Installed) "已安装" else "缺失"} · ${Formatters.time(app.lastSnapshotAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = IosTertiaryText,
                )
            }
            RiskChip(app.riskLevel)
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = IosTertiaryText)
        }
    }
}
