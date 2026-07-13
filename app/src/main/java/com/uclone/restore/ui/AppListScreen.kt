package com.uclone.restore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uclone.restore.model.AppEntry
import com.uclone.restore.util.Formatters

@Composable
fun AppListScreen(state: UiState, viewModel: UCloneViewModel, modifier: Modifier, openDetail: () -> Unit) {
    var searchExpanded by remember { mutableStateOf(state.search.isNotBlank()) }
    var selectedFilters by remember { mutableStateOf(setOf(AppListFilter.ALL)) }
    val query = state.search.trim().lowercase()
    val apps = state.apps.filter {
        val matchesQuery = query.isEmpty() ||
            it.label.lowercase().contains(query) ||
            it.packageName.lowercase().contains(query)
        matchesQuery && selectedFilters.matches(it)
    }
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PageDescription(
                "查找 App、确认两侧安装状态，并管理首页收藏。",
                modifier = Modifier.weight(1f),
            )
            if (!searchExpanded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppFilterButton(selectedFilters) { selectedFilters = it }
                    UtilityIconButton(
                        imageVector = Icons.Default.Search,
                        contentDescription = "搜索",
                        onClick = { searchExpanded = true },
                    )
                }
            }
        }
        if (searchExpanded) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppFilterButton(selectedFilters) { selectedFilters = it }
                OutlinedTextField(
                    value = state.search,
                    onValueChange = viewModel::updateSearch,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("App 名称或包名") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    shape = MaterialTheme.shapes.medium,
                    singleLine = true,
                )
                CompactActionButton(
                    text = "收起",
                    onClick = {
                        viewModel.updateSearch("")
                        searchExpanded = false
                    },
                )
            }
        }
        if (apps.isEmpty()) {
            SectionCard(if (query.isEmpty()) "没有可显示的 App" else "没有匹配结果") {
                Text(
                    if (query.isEmpty()) "调整筛选条件后重试。" else "检查名称、包名或清除搜索条件。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                "${apps.size} 个 App",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(apps, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        favorite = app.packageName in state.settings.favoritePackages,
                        mainUserId = state.settings.mainUserId,
                        cloneUserId = state.settings.cloneUserId,
                        onFavorite = { viewModel.toggleFavorite(app.packageName) },
                        onClick = {
                            viewModel.selectPackage(app.packageName)
                            openDetail()
                        },
                    )
                }
            }
        }
    }
}

private enum class AppListFilter(val label: String) {
    ALL("显示全部"),
    DUAL_SYSTEM("双侧已安装"),
    USER("用户 App"),
    SYSTEM("系统 App"),
}

private fun Set<AppListFilter>.matches(app: AppEntry): Boolean {
    if (AppListFilter.ALL in this) return true
    return all { filter ->
        when (filter) {
            AppListFilter.ALL -> true
            AppListFilter.DUAL_SYSTEM -> app.user0Installed && app.user10Installed
            AppListFilter.USER -> !app.isSystemApp
            AppListFilter.SYSTEM -> app.isSystemApp
        }
    }
}

private fun Set<AppListFilter>.toggle(filter: AppListFilter): Set<AppListFilter> {
    if (filter == AppListFilter.ALL) return setOf(AppListFilter.ALL)
    val next = if (filter in this) this - filter else (this - AppListFilter.ALL) + filter
    return next.ifEmpty { setOf(AppListFilter.ALL) }
}

@Composable
private fun AppFilterButton(selectedFilters: Set<AppListFilter>, onChange: (Set<AppListFilter>) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val active = AppListFilter.ALL !in selectedFilters
    Box {
        UtilityIconButton(
            imageVector = Icons.Default.FilterList,
            contentDescription = "筛选",
            onClick = { expanded = true },
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            selected = active,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppListFilter.entries.forEach { filter ->
                DropdownMenuItem(
                    text = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = filter in selectedFilters, onCheckedChange = null)
                            Text(filter.label)
                        }
                    },
                    onClick = {
                        onChange(selectedFilters.toggle(filter))
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppEntry,
    favorite: Boolean,
    mainUserId: Int,
    cloneUserId: Int,
    onFavorite: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.ucloneColors.groupedSurface,
        border = androidx.compose.foundation.BorderStroke(
            0.5.dp,
            MaterialTheme.ucloneColors.separator.copy(alpha = 0.5f),
        ),
        shadowElevation = 1.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app.packageName)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(app.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "user$mainUserId ${if (app.user0Installed) "已安装" else "未安装"} · user$cloneUserId ${if (app.user10Installed) "已安装" else "未安装"} · ${Formatters.kilobytes(app.snapshotSizeKb)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            UtilityIconButton(
                imageVector = if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = if (favorite) "取消收藏" else "收藏",
                onClick = onFavorite,
                tint = if (favorite) MaterialTheme.ucloneColors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                selected = favorite,
            )
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
