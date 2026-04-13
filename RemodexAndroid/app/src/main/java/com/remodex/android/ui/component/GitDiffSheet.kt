package com.remodex.android.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remodex.android.core.model.DiffFileAction
import com.remodex.android.core.model.DiffFileChunk
import com.remodex.android.core.model.splitUnifiedDiffByFile

private val DiffGreen = Color(0xFF22C55E)
private val DiffRed = Color(0xFFF04444)
private val DiffBlue = Color(0xFF60A5FA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitDiffSheet(
    title: String = "Repository Changes",
    patch: String? = null,
    chunks: List<DiffFileChunk> = emptyList(),
    emptyLabel: String = "No changes",
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val resolvedChunks = remember(patch, chunks) {
        if (chunks.isNotEmpty()) {
            chunks
        } else if (!patch.isNullOrBlank()) {
            splitUnifiedDiffByFile(patch)
        } else {
            emptyList()
        }
    }
    val expandedFiles = remember { mutableStateMapOf<String, Boolean>() }
    val allExpanded = resolvedChunks.isNotEmpty() && resolvedChunks.all { expandedFiles[it.path] == true }

    // Default to all expanded on first show
    if (expandedFiles.isEmpty() && resolvedChunks.isNotEmpty()) {
        resolvedChunks.forEach { expandedFiles[it.path] = true }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }

            if (resolvedChunks.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    emptyLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
            } else {
                // Summary + expand/collapse toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${resolvedChunks.size} file${if (resolvedChunks.size == 1) "" else "s"} changed",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        if (allExpanded) {
                            expandedFiles.keys.forEach { expandedFiles[it] = false }
                        } else {
                            resolvedChunks.forEach { expandedFiles[it.path] = true }
                        }
                    }) {
                        Text(
                            if (allExpanded) "Collapse All" else "Expand All",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                // File cards
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(resolvedChunks, key = { it.path }) { chunk ->
                        val isExpanded = expandedFiles[chunk.path] == true
                        DiffFileCard(
                            chunk = chunk,
                            isExpanded = isExpanded,
                            onToggle = { expandedFiles[chunk.path] = !isExpanded },
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DiffFileCard(
    chunk: DiffFileChunk,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isExpanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))

            // Action icon
            Icon(
                chunk.action.icon(),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = chunk.action.color(),
            )
            Spacer(Modifier.width(6.dp))

            // File name
            Text(
                chunk.compactPath,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.width(6.dp))

            // Action badge
            Text(
                chunk.action.label,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                color = chunk.action.color(),
                modifier = Modifier
                    .background(
                        chunk.action.color().copy(alpha = 0.12f),
                        RoundedCornerShape(50),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )

            Spacer(Modifier.width(6.dp))

            // +/- counts
            if (chunk.additions > 0) {
                Text(
                    "+${chunk.additions}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = DiffGreen,
                )
                Spacer(Modifier.width(4.dp))
            }
            if (chunk.deletions > 0) {
                Text(
                    "-${chunk.deletions}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = DiffRed,
                )
            }
        }

        // Directory subtitle
        val dir = chunk.directoryPath
        if (dir != null && dir != chunk.compactPath) {
            Text(
                dir,
                modifier = Modifier.padding(start = 36.dp, end = 12.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Expanded diff content
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                DiffBlock(
                    code = chunk.diffText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DiffBlock(
    code: String,
    modifier: Modifier = Modifier,
) {
    val neutralTextColor = MaterialTheme.colorScheme.onSurface
    Column(modifier = modifier) {
        code.lines().forEach { rawLine ->
            val kind = classifyDiffLine(rawLine)
            when (kind) {
                DiffLineKind.META -> Unit
                DiffLineKind.HUNK -> Spacer(Modifier.height(6.dp))
                else -> {
                    val content = when (kind) {
                        DiffLineKind.ADDITION,
                        DiffLineKind.DELETION -> rawLine.drop(1)
                        DiffLineKind.NEUTRAL -> rawLine.removePrefix(" ")
                        else -> rawLine
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(diffLineBackground(kind)),
                    ) {
                        Spacer(
                            modifier = Modifier
                                .width(if (kind == DiffLineKind.ADDITION || kind == DiffLineKind.DELETION) 2.dp else 0.dp)
                                .height(18.dp)
                                .background(diffIndicatorColor(kind)),
                        )
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            ),
                            color = diffTextColor(kind, neutralTextColor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun DiffFileAction.icon(): ImageVector = when (this) {
    DiffFileAction.EDITED -> Icons.Rounded.Edit
    DiffFileAction.ADDED -> Icons.Rounded.Add
    DiffFileAction.DELETED -> Icons.Rounded.Remove
    DiffFileAction.RENAMED -> Icons.AutoMirrored.Rounded.ArrowForward
}

private fun DiffFileAction.color(): Color = when (this) {
    DiffFileAction.EDITED -> Color(0xFFFFA726)
    DiffFileAction.ADDED -> DiffGreen
    DiffFileAction.DELETED -> DiffRed
    DiffFileAction.RENAMED -> Color(0xFF60A5FA)
}

private enum class DiffLineKind {
    ADDITION,
    DELETION,
    HUNK,
    META,
    NEUTRAL,
}

private fun classifyDiffLine(line: String): DiffLineKind = when {
    line.startsWith("@@") -> DiffLineKind.HUNK
    line.startsWith("diff ") || line.startsWith("index ") || line.startsWith("---") || line.startsWith("+++") ||
        line.startsWith("new file mode") || line.startsWith("deleted file mode") -> DiffLineKind.META
    line.startsWith("+") && !line.startsWith("+++") -> DiffLineKind.ADDITION
    line.startsWith("-") && !line.startsWith("---") -> DiffLineKind.DELETION
    else -> DiffLineKind.NEUTRAL
}

private fun diffTextColor(kind: DiffLineKind, neutralColor: Color): Color = when (kind) {
    DiffLineKind.ADDITION -> DiffGreen
    DiffLineKind.DELETION -> DiffRed
    DiffLineKind.HUNK -> DiffBlue
    DiffLineKind.META, DiffLineKind.NEUTRAL -> neutralColor
}

private fun diffIndicatorColor(kind: DiffLineKind): Color = when (kind) {
    DiffLineKind.ADDITION -> DiffGreen
    DiffLineKind.DELETION -> DiffRed
    else -> Color.Transparent
}

private fun diffLineBackground(kind: DiffLineKind): Color = when (kind) {
    DiffLineKind.ADDITION -> DiffGreen.copy(alpha = 0.12f)
    DiffLineKind.DELETION -> DiffRed.copy(alpha = 0.12f)
    else -> Color.Transparent
}
