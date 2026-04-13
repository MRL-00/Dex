package com.mattl_nz.dex.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mattl_nz.dex.core.model.GitBranchesResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitBranchSelector(
    branches: GitBranchesResult?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("Switch branch", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (branches == null || branches.branches.isEmpty()) {
                Text(
                    "No branches available",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn {
                    items(branches.branches) { branch ->
                        TextButton(
                            onClick = { onSelect(branch.name) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                branch.name,
                                fontWeight = if (branch.isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (branch.isCurrent) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (branch.isDefault) {
                                Text(
                                    "default",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
