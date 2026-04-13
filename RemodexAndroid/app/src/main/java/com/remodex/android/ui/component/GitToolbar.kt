package com.remodex.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Commit
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.remodex.android.core.model.GitRepoSyncResult

/**
 * Git actions bottom sheet menu matching the iOS layout.
 * Sections: Update, Write, Recovery.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitActionsSheet(
    gitStatus: GitRepoSyncResult?,
    onUpdate: () -> Unit,
    onCommit: () -> Unit,
    onPush: () -> Unit,
    onCommitAndPush: () -> Unit,
    onDiff: () -> Unit,
    onDiscard: () -> Unit,
    onBranch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            // Update section
            SectionLabel("Update")
            GitMenuItem(
                icon = { Icon(Icons.Rounded.Refresh, null, Modifier.size(20.dp)) },
                text = "Update",
                onClick = {
                    onUpdate()
                    onDismiss()
                },
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
            )

            // Write section
            SectionLabel("Write")
            GitMenuItem(
                icon = { Icon(Icons.Rounded.Commit, null, Modifier.size(20.dp)) },
                text = "Commit",
                onClick = {
                    onCommit()
                    onDismiss()
                },
            )
            GitMenuItem(
                icon = { Icon(Icons.Rounded.CloudUpload, null, Modifier.size(20.dp)) },
                text = "Push",
                onClick = {
                    onPush()
                    onDismiss()
                },
            )
            GitMenuItem(
                icon = { Icon(Icons.Rounded.CloudUpload, null, Modifier.size(20.dp)) },
                text = "Commit & Push",
                onClick = {
                    onCommitAndPush()
                    onDismiss()
                },
            )
            GitMenuItem(
                icon = { Icon(Icons.Rounded.Description, null, Modifier.size(20.dp)) },
                text = "View Changes",
                onClick = {
                    onDiff()
                    onDismiss()
                },
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
            )

            // Recovery section
            SectionLabel("Recovery", color = MaterialTheme.colorScheme.error)
            GitMenuItem(
                icon = {
                    Icon(
                        Icons.Rounded.DeleteOutline, null,
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                text = "Discard Local Changes",
                onClick = {
                    onDiscard()
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun SectionLabel(
    text: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
}

@Composable
private fun GitMenuItem(
    icon: @Composable () -> Unit,
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(14.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/**
 * Compact diff stats badge for the top bar.
 * Shows +additions -deletions in a colored pill.
 */
@Composable
fun DiffStatsBadge(
    additions: Int,
    deletions: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (additions == 0 && deletions == 0) return
    val diffGreen = androidx.compose.ui.graphics.Color(0xFF22C55E)
    val diffRed = androidx.compose.ui.graphics.Color(0xFFF04444)

    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "+$additions",
            style = MaterialTheme.typography.labelSmall,
            color = diffGreen,
        )
        Text(
            "-$deletions",
            style = MaterialTheme.typography.labelSmall,
            color = diffRed,
        )
    }
}
