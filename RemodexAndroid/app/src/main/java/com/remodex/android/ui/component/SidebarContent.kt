package com.remodex.android.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remodex.android.core.model.RemodexUiState
import com.remodex.android.core.model.ThreadSummary

private val SidebarDiffGreen = androidx.compose.ui.graphics.Color(0xFF22C55E)
private val SidebarDiffRed = androidx.compose.ui.graphics.Color(0xFFF04444)

private data class ProjectGroup(
    val name: String,
    val threads: List<ThreadSummary>,
)

private fun groupThreadsByProject(threads: List<ThreadSummary>): List<ProjectGroup> {
    val grouped = threads.groupBy { thread ->
        val cwd = thread.cwd?.trimEnd('/')
        if (cwd.isNullOrBlank()) "Ungrouped"
        else cwd.substringAfterLast('/').ifBlank { "Ungrouped" }
    }
    return grouped.map { (name, threads) ->
        ProjectGroup(name, threads.sortedByDescending { it.updatedAtMillis ?: 0L })
    }.sortedByDescending { group ->
        group.threads.maxOfOrNull { it.updatedAtMillis ?: 0L } ?: 0L
    }
}

private fun formatTimeAgo(millis: Long?): String {
    if (millis == null) return ""
    val now = System.currentTimeMillis()
    val diff = now - millis
    if (diff < 0) return ""
    val minutes = diff / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        days < 7 -> "${days}d"
        else -> "${days / 7}w"
    }
}

@Composable
fun SidebarContent(
    uiState: RemodexUiState,
    onNewChat: () -> Unit,
    onSelectThread: (String) -> Unit,
    onShowSettings: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    // Track collapsed state per group, default expanded
    // Track expanded state per group; groups default to collapsed
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    val filteredThreads = if (searchQuery.isBlank()) {
        uiState.threads
    } else {
        uiState.threads.filter { thread ->
            thread.title.contains(searchQuery, ignoreCase = true) ||
                (thread.preview?.contains(searchQuery, ignoreCase = true) == true)
        }
    }
    val projectGroups = groupThreadsByProject(filteredThreads)

    // Connected Mac display name
    val connectedMacName = uiState.trustedMacs.values
        .maxByOrNull { it.lastUsedAt ?: it.lastPairedAt }
        ?.displayName

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(top = 16.dp),
    ) {
        // App header with icon
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemodexBrandIcon(
                modifier = Modifier
                    .size(60.dp),
                contentDescription = "Remodex app icon",
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Remodex",
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Spacer(Modifier.height(14.dp))

        // Search bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            "Search conversations",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // New Chat button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNewChat)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "New Chat",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(Modifier.height(4.dp))

        // Loading state when no threads yet but connected
        if (uiState.threads.isEmpty() && uiState.isConnected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Loading threads...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }

        // Project-grouped thread list
        LazyColumn(
            modifier = Modifier.weight(1f),
        ) {
            projectGroups.forEach { group ->
                val isCollapsed = expandedGroups[group.name] != true

                // Project header (clickable to collapse/expand)
                item(key = "header_${group.name}") {
                    val groupDiffTotals = group.threads
                        .asSequence()
                        .mapNotNull { thread -> uiState.gitStatusByThread[thread.id]?.diffTotals }
                        .firstOrNull { totals -> totals.additions > 0 || totals.deletions > 0 }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedGroups[group.name] = isCollapsed
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Computer,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            group.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (groupDiffTotals != null) {
                            Text(
                                "+${groupDiffTotals.additions}",
                                style = MaterialTheme.typography.labelSmall,
                                color = SidebarDiffGreen,
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                "-${groupDiffTotals.deletions}",
                                style = MaterialTheme.typography.labelSmall,
                                color = SidebarDiffRed,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        IconButton(
                            onClick = onNewChat,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Add,
                                contentDescription = "New chat in ${group.name}",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            if (isCollapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                            contentDescription = if (isCollapsed) "Expand" else "Collapse",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }

                // Threads in this project (animated collapse)
                if (!isCollapsed) {
                    items(
                        count = group.threads.size,
                        key = { group.threads[it].id },
                    ) { index ->
                        val thread = group.threads[index]
                        val selected = uiState.selectedThreadId == thread.id
                        val isRunning = thread.id in uiState.runningThreadIds
                        val timeAgo = formatTimeAgo(thread.updatedAtMillis)

                        // Guard against blank/null titles
                        val displayTitle = thread.title.ifBlank { "New Thread" }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectThread(thread.id) }
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    } else {
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                                    },
                                )
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isRunning) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondary),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                displayTitle,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            if (timeAgo.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    timeAgo,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom section: Settings + Connected Mac
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onShowSettings)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = "Settings",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (uiState.isConnected) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Connected to Mac",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                if (!connectedMacName.isNullOrBlank()) {
                    Text(
                        connectedMacName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
