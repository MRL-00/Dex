package com.remodex.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.remodex.android.core.model.RemodexUiState
import com.remodex.android.core.model.ThreadSummary

private val SidebarDiffGreen = androidx.compose.ui.graphics.Color(0xFF22C55E)
private val SidebarDiffRed = androidx.compose.ui.graphics.Color(0xFFF04444)
private const val NoProjectGroupId = "__no_project__"

private data class ProjectGroup(
    val id: String,
    val name: String,
    val projectPath: String?,
    val threads: List<ThreadSummary>,
)

private fun normalizedProjectPath(rawPath: String?): String? {
    return rawPath?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
}

private fun projectGroupLabel(projectPath: String?): String {
    val normalized = normalizedProjectPath(projectPath) ?: return "No Repo"
    return normalized.substringAfterLast('/').ifBlank { "No Repo" }
}

private fun synchronizedProjectOrder(
    currentOrder: List<String>,
    groups: List<ProjectGroup>,
): List<String> {
    val visibleProjectIds = groups
        .filter { it.projectPath != null }
        .map { it.id }
    val visibleSet = visibleProjectIds.toSet()
    val ordered = mutableListOf<String>()

    currentOrder.forEach { groupId ->
        if (groupId in visibleSet && groupId !in ordered) {
            ordered += groupId
        }
    }
    visibleProjectIds.forEach { groupId ->
        if (groupId !in ordered) {
            ordered += groupId
        }
    }

    return ordered
}

private fun moveProjectOrderToIndex(
    currentOrder: List<String>,
    projectId: String,
    destinationIndex: Int,
): List<String> {
    val currentIndex = currentOrder.indexOf(projectId)
    if (currentIndex == -1) return currentOrder

    val safeDestinationIndex = destinationIndex.coerceIn(0, currentOrder.lastIndex)
    if (safeDestinationIndex == currentIndex) return currentOrder

    val reordered = currentOrder.toMutableList()
    val moved = reordered.removeAt(currentIndex)
    reordered.add(safeDestinationIndex, moved)
    return reordered
}

private fun headerKeyForProject(groupId: String): String = "header_$groupId"

private fun projectIdFromHeaderKey(key: Any?): String? {
    val rawKey = key as? String ?: return null
    return rawKey.removePrefix("header_").takeIf { rawKey.startsWith("header_") }
}

private fun groupThreadsByProject(
    threads: List<ThreadSummary>,
    preferredProjectOrder: List<String>,
): List<ProjectGroup> {
    val grouped = threads.groupBy { thread ->
        normalizedProjectPath(thread.cwd) ?: NoProjectGroupId
    }

    val groups = grouped.map { (projectId, projectThreads) ->
        val sortedThreads = projectThreads.sortedByDescending { it.updatedAtMillis ?: 0L }
        ProjectGroup(
            id = projectId,
            name = projectGroupLabel(if (projectId == NoProjectGroupId) null else projectId),
            projectPath = if (projectId == NoProjectGroupId) null else projectId,
            threads = sortedThreads,
        )
    }

    val synchronizedOrder = synchronizedProjectOrder(preferredProjectOrder, groups)
    val orderIndexById = synchronizedOrder.withIndex().associate { it.value to it.index }

    return groups.sortedWith(
        compareBy<ProjectGroup> { if (it.projectPath == null) 1 else 0 }
            .thenBy { orderIndexById[it.id] ?: Int.MAX_VALUE }
            .thenByDescending { group -> group.threads.maxOfOrNull { it.updatedAtMillis ?: 0L } ?: 0L }
            .thenBy { it.name.lowercase() },
    )
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
    projectOrder: List<String>,
    onProjectOrderChanged: (List<String>) -> Unit,
    onStartThreadInProject: (String?) -> Unit,
    onSelectThread: (String) -> Unit,
    onShowSettings: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    var showNewChatChooser by remember { mutableStateOf(false) }
    var isEditingProjectOrder by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var draggingProjectId by remember { mutableStateOf<String?>(null) }
    var draggingProjectOffsetY by remember { mutableStateOf(0f) }

    val allProjectGroups = groupThreadsByProject(uiState.threads, projectOrder)
    val synchronizedOrder = synchronizedProjectOrder(projectOrder, allProjectGroups)

    LaunchedEffect(synchronizedOrder) {
        if (synchronizedOrder != projectOrder) {
            onProjectOrderChanged(synchronizedOrder)
        }
    }

    val filteredThreads = if (searchQuery.isBlank()) {
        uiState.threads
    } else {
        uiState.threads.filter { thread ->
            thread.title.contains(searchQuery, ignoreCase = true) ||
                (thread.preview?.contains(searchQuery, ignoreCase = true) == true) ||
                projectGroupLabel(thread.cwd).contains(searchQuery, ignoreCase = true)
        }
    }
    val projectGroups = groupThreadsByProject(filteredThreads, synchronizedOrder)

    val connectedMacName = uiState.trustedMacs.values
        .maxByOrNull { it.lastUsedAt ?: it.lastPairedAt }
        ?.displayName

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(top = 16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemodexBrandIcon(
                modifier = Modifier.size(60.dp),
                contentDescription = "Remodex app icon",
            )
            Spacer(Modifier.width(12.dp))
            Text("Remodex", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(Modifier.height(14.dp))

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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { showNewChatChooser = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(10.dp))
                Text("New Chat", style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = { isEditingProjectOrder = !isEditingProjectOrder }) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (isEditingProjectOrder) "Done" else "Edit")
            }
        }

        Spacer(Modifier.height(4.dp))

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

        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
        ) {
            projectGroups.forEach { group ->
                val isCollapsed = expandedGroups[group.id] != true
                val groupDiffTotals = group.threads
                    .asSequence()
                    .mapNotNull { thread -> uiState.gitStatusByThread[thread.id]?.diffTotals }
                    .firstOrNull { totals -> totals.additions > 0 || totals.deletions > 0 }
                val repoOnlyOrder = synchronizedOrder

                item(key = headerKeyForProject(group.id)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                translationY = if (draggingProjectId == group.id) {
                                    draggingProjectOffsetY
                                } else {
                                    0f
                                }
                            }
                            .zIndex(if (draggingProjectId == group.id) 1f else 0f)
                            .clickable { expandedGroups[group.id] = isCollapsed }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (group.projectPath != null) Icons.Rounded.Computer else Icons.Rounded.CloudQueue,
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                            Spacer(Modifier.width(4.dp))
                        }
                        IconButton(
                            onClick = { onStartThreadInProject(group.projectPath) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Add,
                                contentDescription = "New chat in ${group.name}",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isEditingProjectOrder && group.projectPath != null) {
                            Icon(
                                Icons.Rounded.DragHandle,
                                contentDescription = "Drag to reorder ${group.name}",
                                modifier = Modifier
                                    .size(22.dp)
                                    .pointerInput(group.id, repoOnlyOrder, projectGroups) {
                                        detectDragGestures(
                                            onDragStart = {
                                                draggingProjectId = group.id
                                                draggingProjectOffsetY = 0f
                                            },
                                            onDragEnd = {
                                                draggingProjectId = null
                                                draggingProjectOffsetY = 0f
                                            },
                                            onDragCancel = {
                                                draggingProjectId = null
                                                draggingProjectOffsetY = 0f
                                            },
                                        ) { change, dragAmount ->
                                            change.consume()
                                            draggingProjectOffsetY += dragAmount.y

                                            val draggedItemInfo = listState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { itemInfo ->
                                                    projectIdFromHeaderKey(itemInfo.key) == group.id
                                                } ?: return@detectDragGestures

                                            val draggedMidpoint =
                                                draggedItemInfo.offset + (draggedItemInfo.size / 2f) + draggingProjectOffsetY

                                            val targetItemInfo = listState.layoutInfo.visibleItemsInfo
                                                .filter { itemInfo ->
                                                    val projectId = projectIdFromHeaderKey(itemInfo.key)
                                                    projectId != null && projectId != group.id
                                                }
                                                .firstOrNull { itemInfo ->
                                                    draggedMidpoint >= itemInfo.offset &&
                                                        draggedMidpoint <= itemInfo.offset + itemInfo.size
                                                } ?: return@detectDragGestures

                                            val targetProjectId = projectIdFromHeaderKey(targetItemInfo.key)
                                                ?: return@detectDragGestures
                                            val targetIndex = repoOnlyOrder.indexOf(targetProjectId)
                                            if (targetIndex == -1) return@detectDragGestures

                                            val reordered = moveProjectOrderToIndex(
                                                currentOrder = repoOnlyOrder,
                                                projectId = group.id,
                                                destinationIndex = targetIndex,
                                            )
                                            if (reordered != repoOnlyOrder) {
                                                onProjectOrderChanged(reordered)
                                                draggingProjectOffsetY +=
                                                    (draggedItemInfo.offset - targetItemInfo.offset).toFloat()
                                            }
                                        }
                                    },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Icon(
                            if (isCollapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                            contentDescription = if (isCollapsed) "Expand" else "Collapse",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }

                if (!isCollapsed) {
                    items(
                        count = group.threads.size,
                        key = { group.threads[it].id },
                    ) { index ->
                        val thread = group.threads[index]
                        val selected = uiState.selectedThreadId == thread.id
                        val isRunning = thread.id in uiState.runningThreadIds
                        val timeAgo = formatTimeAgo(thread.updatedAtMillis)
                        val displayTitle = thread.title.ifBlank { "New Thread" }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectThread(thread.id) }
                                .background(
                                    if (selected) {
                                        Color(0xFF3DDC84).copy(alpha = 0.15f)
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

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.clickable(onClick = onShowSettings),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = "Settings",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (uiState.isConnected) {
                    Column(horizontalAlignment = Alignment.End) {
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
    }

    if (showNewChatChooser) {
        AlertDialog(
            onDismissRequest = { showNewChatChooser = false },
            title = { Text("Choose a repo") },
            text = {
                Column {
                    Text(
                        "Pick which repo or workspace the new chat should use.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    allProjectGroups.forEach { group ->
                        TextButton(
                            onClick = {
                                showNewChatChooser = false
                                onStartThreadInProject(group.projectPath)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (group.projectPath != null) {
                                        Icons.Rounded.Computer
                                    } else {
                                        Icons.Rounded.CloudQueue
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        group.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    group.projectPath?.let { projectPath ->
                                        Text(
                                            projectPath,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (allProjectGroups.isEmpty()) {
                        Text(
                            "No recent repos yet. Starting without a repo will create a global chat.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNewChatChooser = false
                        onStartThreadInProject(null)
                    },
                ) {
                    Text("No Repo")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewChatChooser = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
